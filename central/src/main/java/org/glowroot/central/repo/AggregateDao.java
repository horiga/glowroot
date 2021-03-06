/*
 * Copyright 2015-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.central.repo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.utils.UUIDs;
import com.google.common.base.Charsets;
import com.google.common.base.Strings;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.hash.Hashing;
import com.google.common.primitives.Ints;
import com.google.protobuf.AbstractMessage;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.api.Instrumentation;
import org.glowroot.central.util.Messages;
import org.glowroot.central.util.MoreFutures;
import org.glowroot.central.util.Sessions;
import org.glowroot.common.config.ConfigDefaults;
import org.glowroot.common.config.StorageConfig;
import org.glowroot.common.live.ImmutableOverviewAggregate;
import org.glowroot.common.live.ImmutablePercentileAggregate;
import org.glowroot.common.live.ImmutableThroughputAggregate;
import org.glowroot.common.live.ImmutableTransactionQuery;
import org.glowroot.common.live.LiveAggregateRepository.OverallQuery;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.common.live.LiveAggregateRepository.ThroughputAggregate;
import org.glowroot.common.live.LiveAggregateRepository.TransactionQuery;
import org.glowroot.common.model.LazyHistogram;
import org.glowroot.common.model.LazyHistogram.ScratchBuffer;
import org.glowroot.common.model.MutableProfile;
import org.glowroot.common.model.MutableQuery;
import org.glowroot.common.model.OverallErrorSummaryCollector;
import org.glowroot.common.model.OverallSummaryCollector;
import org.glowroot.common.model.ProfileCollector;
import org.glowroot.common.model.QueryCollector;
import org.glowroot.common.model.ServiceCallCollector;
import org.glowroot.common.model.TransactionErrorSummaryCollector;
import org.glowroot.common.model.TransactionErrorSummaryCollector.ErrorSummarySortOrder;
import org.glowroot.common.model.TransactionSummaryCollector;
import org.glowroot.common.model.TransactionSummaryCollector.SummarySortOrder;
import org.glowroot.common.repo.AggregateRepository;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.ConfigRepository.RollupConfig;
import org.glowroot.common.repo.MutableAggregate;
import org.glowroot.common.repo.MutableThreadStats;
import org.glowroot.common.repo.MutableTimer;
import org.glowroot.common.repo.Utils;
import org.glowroot.common.repo.util.ThreadStatsCreator;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.common.util.Styles;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AdvancedConfig;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.OldAggregatesByType;
import org.glowroot.wire.api.model.AggregateOuterClass.OldTransactionAggregate;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class AggregateDao implements AggregateRepository {

    private static final Logger logger = LoggerFactory.getLogger(AggregateDao.class);

    private static final String LCS = "compaction = { 'class' : 'LeveledCompactionStrategy' }";

    private static final Table summaryTable = ImmutableTable.builder()
            .partialName("summary")
            .addColumns(ImmutableColumn.of("total_duration_nanos", "double"))
            .addColumns(ImmutableColumn.of("transaction_count", "bigint"))
            .summary(true)
            .fromInclusive(false)
            .build();

    private static final Table errorSummaryTable = ImmutableTable.builder()
            .partialName("error_summary")
            .addColumns(ImmutableColumn.of("error_count", "bigint"))
            .addColumns(ImmutableColumn.of("transaction_count", "bigint"))
            .summary(true)
            .fromInclusive(false)
            .build();

    private static final Table overviewTable = ImmutableTable.builder()
            .partialName("overview")
            .addColumns(ImmutableColumn.of("total_duration_nanos", "double"))
            .addColumns(ImmutableColumn.of("transaction_count", "bigint"))
            .addColumns(ImmutableColumn.of("async_transactions", "boolean"))
            .addColumns(ImmutableColumn.of("main_thread_root_timers", "blob"))
            .addColumns(ImmutableColumn.of("aux_thread_root_timers", "blob"))
            .addColumns(ImmutableColumn.of("async_root_timers", "blob"))
            .addColumns(ImmutableColumn.of("main_thread_total_cpu_nanos", "double")) // nullable
            .addColumns(ImmutableColumn.of("main_thread_total_blocked_nanos", "double")) // nullable
            .addColumns(ImmutableColumn.of("main_thread_total_waited_nanos", "double")) // nullable
            .addColumns(ImmutableColumn.of("main_thread_total_allocated_bytes", "double")) // nullable
            .addColumns(ImmutableColumn.of("aux_thread_total_cpu_nanos", "double")) // nullable
            .addColumns(ImmutableColumn.of("aux_thread_total_blocked_nanos", "double")) // nullable
            .addColumns(ImmutableColumn.of("aux_thread_total_waited_nanos", "double")) // nullable
            .addColumns(ImmutableColumn.of("aux_thread_total_allocated_bytes", "double")) // nullable
            .summary(false)
            .fromInclusive(true)
            .build();

    private static final Table histogramTable = ImmutableTable.builder()
            .partialName("histogram")
            .addColumns(ImmutableColumn.of("total_duration_nanos", "double"))
            .addColumns(ImmutableColumn.of("transaction_count", "bigint"))
            .addColumns(ImmutableColumn.of("duration_nanos_histogram", "blob"))
            .summary(false)
            .fromInclusive(true)
            .build();

    private static final Table throughputTable = ImmutableTable.builder()
            .partialName("throughput")
            .addColumns(ImmutableColumn.of("transaction_count", "bigint"))
            .summary(false)
            .fromInclusive(true)
            .build();

    private static final Table queryTable = ImmutableTable.builder()
            .partialName("query")
            .addColumns(ImmutableColumn.of("query_type", "varchar"))
            .addColumns(ImmutableColumn.of("truncated_query_text", "varchar"))
            // empty when truncated_query_text is really full query text
            // (not null since this column must be used in clustering key)
            .addColumns(ImmutableColumn.of("full_query_text_sha1", "varchar"))
            .addColumns(ImmutableColumn.of("total_duration_nanos", "double"))
            .addColumns(ImmutableColumn.of("execution_count", "bigint"))
            .addColumns(ImmutableColumn.of("total_rows", "bigint"))
            .addClusterKey("query_type")
            .addClusterKey("truncated_query_text")
            .addClusterKey("full_query_text_sha1") // need this for uniqueness
            .summary(false)
            .fromInclusive(false)
            .build();

    private static final Table serviceCallTable = ImmutableTable.builder()
            .partialName("service_call")
            .addColumns(ImmutableColumn.of("service_call_type", "varchar"))
            .addColumns(ImmutableColumn.of("service_call_text", "varchar"))
            .addColumns(ImmutableColumn.of("total_duration_nanos", "double"))
            .addColumns(ImmutableColumn.of("execution_count", "bigint"))
            .addClusterKey("service_call_type")
            .addClusterKey("service_call_text")
            .summary(false)
            .fromInclusive(false)
            .build();

    private static final Table mainThreadProfileTable = ImmutableTable.builder()
            .partialName("main_thread_profile")
            .addColumns(ImmutableColumn.of("main_thread_profile", "blob"))
            .summary(false)
            .fromInclusive(false)
            .build();

    private static final Table auxThreadProfileTable = ImmutableTable.builder()
            .partialName("aux_thread_profile")
            .addColumns(ImmutableColumn.of("aux_thread_profile", "blob"))
            .summary(false)
            .fromInclusive(false)
            .build();

    private final Session session;
    private final AgentDao agentDao;
    private final TransactionTypeDao transactionTypeDao;
    private final FullQueryTextDao fullQueryTextDao;
    private final ConfigRepository configRepository;
    private final Clock clock;

    // list index is rollupLevel
    private final Map<Table, List<PreparedStatement>> insertOverallPS;
    private final Map<Table, List<PreparedStatement>> insertTransactionPS;
    private final Map<Table, List<PreparedStatement>> readOverallPS;
    private final Map<Table, List<PreparedStatement>> readOverallForRollupPS;
    private final Map<Table, PreparedStatement> readOverallForRollupFromChildPS;
    private final Map<Table, List<PreparedStatement>> readTransactionPS;
    private final Map<Table, List<PreparedStatement>> readTransactionForRollupPS;
    private final Map<Table, PreparedStatement> readTransactionForRollupFromChildPS;

    private final List<PreparedStatement> existsMainThreadProfileOverallPS;
    private final List<PreparedStatement> existsMainThreadProfileTransactionPS;
    private final List<PreparedStatement> existsAuxThreadProfileOverallPS;
    private final List<PreparedStatement> existsAuxThreadProfileTransactionPS;

    private final List<PreparedStatement> insertNeedsRollup;
    private final List<PreparedStatement> readNeedsRollup;
    private final List<PreparedStatement> deleteNeedsRollup;

    private final PreparedStatement insertNeedsRollupFromChild;
    private final PreparedStatement readNeedsRollupFromChild;
    private final PreparedStatement deleteNeedsRollupFromChild;

    private final ImmutableList<Table> allTables;

    public AggregateDao(Session session, AgentDao agentDao, TransactionTypeDao transactionTypeDao,
            FullQueryTextDao fullQueryTextDao, ConfigRepository configRepository, Clock clock)
            throws Exception {
        this.session = session;
        this.agentDao = agentDao;
        this.transactionTypeDao = transactionTypeDao;
        this.fullQueryTextDao = fullQueryTextDao;
        this.configRepository = configRepository;
        this.clock = clock;

        int count = configRepository.getRollupConfigs().size();
        List<Integer> rollupExpirationHours =
                configRepository.getStorageConfig().rollupExpirationHours();

        allTables = ImmutableList.of(summaryTable, errorSummaryTable, overviewTable,
                histogramTable, throughputTable, queryTable, serviceCallTable,
                mainThreadProfileTable, auxThreadProfileTable);
        Map<Table, List<PreparedStatement>> insertOverallMap = Maps.newHashMap();
        Map<Table, List<PreparedStatement>> insertTransactionMap = Maps.newHashMap();
        Map<Table, List<PreparedStatement>> readOverallMap = Maps.newHashMap();
        Map<Table, List<PreparedStatement>> readOverallForRollupMap = Maps.newHashMap();
        Map<Table, PreparedStatement> readOverallForRollupFromChildMap = Maps.newHashMap();
        Map<Table, List<PreparedStatement>> readTransactionMap = Maps.newHashMap();
        Map<Table, List<PreparedStatement>> readTransactionForRollupMap = Maps.newHashMap();
        Map<Table, PreparedStatement> readTransactionForRollupFromChildMap = Maps.newHashMap();
        for (Table table : allTables) {
            List<PreparedStatement> insertOverallList = Lists.newArrayList();
            List<PreparedStatement> insertTransactionList = Lists.newArrayList();
            List<PreparedStatement> readOverallList = Lists.newArrayList();
            List<PreparedStatement> readOverallForRollupList = Lists.newArrayList();
            List<PreparedStatement> readTransactionList = Lists.newArrayList();
            List<PreparedStatement> readTransactionForRollupList = Lists.newArrayList();
            for (int i = 0; i < count; i++) {
                int expirationHours = rollupExpirationHours.get(i);
                if (table.summary()) {
                    Sessions.createTableWithTWCS(session, createSummaryTableQuery(table, false, i),
                            expirationHours);
                    Sessions.createTableWithTWCS(session, createSummaryTableQuery(table, true, i),
                            expirationHours);
                    insertOverallList.add(session.prepare(insertSummaryPS(table, false, i)));
                    insertTransactionList.add(session.prepare(insertSummaryPS(table, true, i)));
                    readOverallList.add(session.prepare(readSummaryPS(table, false, i)));
                    readOverallForRollupList
                            .add(session.prepare(readSummaryForRollupPS(table, false, i)));
                    readTransactionList.add(session.prepare(readSummaryPS(table, true, i)));
                    readTransactionForRollupList
                            .add(session.prepare(readSummaryForRollupPS(table, true, i)));
                } else {
                    Sessions.createTableWithTWCS(session, createTableQuery(table, false, i),
                            expirationHours);
                    Sessions.createTableWithTWCS(session, createTableQuery(table, true, i),
                            expirationHours);
                    insertOverallList.add(session.prepare(insertPS(table, false, i)));
                    insertTransactionList.add(session.prepare(insertPS(table, true, i)));
                    readOverallList.add(session.prepare(readPS(table, false, i)));
                    readOverallForRollupList.add(session.prepare(readForRollupPS(table, false, i)));
                    readTransactionList.add(session.prepare(readPS(table, true, i)));
                    readTransactionForRollupList
                            .add(session.prepare(readForRollupPS(table, true, i)));
                }
            }
            insertOverallMap.put(table, ImmutableList.copyOf(insertOverallList));
            insertTransactionMap.put(table, ImmutableList.copyOf(insertTransactionList));
            readOverallMap.put(table, ImmutableList.copyOf(readOverallList));
            readOverallForRollupMap.put(table, ImmutableList.copyOf(readOverallForRollupList));
            if (table.summary()) {
                readOverallForRollupFromChildMap.put(table,
                        session.prepare(readSummaryForRollupFromChildPS(table, false, 0)));
            } else {
                readOverallForRollupFromChildMap.put(table,
                        session.prepare(readForRollupFromChildPS(table, false, 0)));
            }
            readTransactionMap.put(table, ImmutableList.copyOf(readTransactionList));
            readTransactionForRollupMap.put(table,
                    ImmutableList.copyOf(readTransactionForRollupList));
            if (table.summary()) {
                readTransactionForRollupFromChildMap.put(table,
                        session.prepare(readSummaryForRollupFromChildPS(table, true, 0)));
            } else {
                readTransactionForRollupFromChildMap.put(table,
                        session.prepare(readForRollupFromChildPS(table, true, 0)));
            }
        }
        this.insertOverallPS = ImmutableMap.copyOf(insertOverallMap);
        this.insertTransactionPS = ImmutableMap.copyOf(insertTransactionMap);
        this.readOverallPS = ImmutableMap.copyOf(readOverallMap);
        this.readOverallForRollupPS = ImmutableMap.copyOf(readOverallForRollupMap);
        this.readOverallForRollupFromChildPS =
                ImmutableMap.copyOf(readOverallForRollupFromChildMap);
        this.readTransactionPS = ImmutableMap.copyOf(readTransactionMap);
        this.readTransactionForRollupPS = ImmutableMap.copyOf(readTransactionForRollupMap);
        this.readTransactionForRollupFromChildPS =
                ImmutableMap.copyOf(readTransactionForRollupFromChildMap);

        List<PreparedStatement> existsMainThreadProfileOverallPS = Lists.newArrayList();
        List<PreparedStatement> existsMainThreadProfileTransactionPS = Lists.newArrayList();
        List<PreparedStatement> existsAuxThreadProfileOverallPS = Lists.newArrayList();
        List<PreparedStatement> existsAuxThreadProfileTransactionPS = Lists.newArrayList();
        for (int i = 0; i < count; i++) {
            existsMainThreadProfileOverallPS
                    .add(session.prepare(existsPS(mainThreadProfileTable, false, i)));
            existsMainThreadProfileTransactionPS
                    .add(session.prepare(existsPS(mainThreadProfileTable, true, i)));
            existsAuxThreadProfileOverallPS
                    .add(session.prepare(existsPS(auxThreadProfileTable, false, i)));
            existsAuxThreadProfileTransactionPS
                    .add(session.prepare(existsPS(auxThreadProfileTable, true, i)));
        }
        this.existsMainThreadProfileOverallPS = existsMainThreadProfileOverallPS;
        this.existsMainThreadProfileTransactionPS = existsMainThreadProfileTransactionPS;
        this.existsAuxThreadProfileOverallPS = existsAuxThreadProfileOverallPS;
        this.existsAuxThreadProfileTransactionPS = existsAuxThreadProfileTransactionPS;

        // since rollup operations are idempotent, any records resurrected after gc_grace_seconds
        // would just create extra work, but not have any other effect
        //
        // 3 hours is chosen to match default max_hint_window_in_ms since hints are stored
        // with a TTL of gc_grace_seconds
        // (see http://www.uberobert.com/cassandra_gc_grace_disables_hinted_handoff)
        long needsRollupGcGraceSeconds = HOURS.toSeconds(3);

        List<PreparedStatement> insertNeedsRollup = Lists.newArrayList();
        List<PreparedStatement> readNeedsRollup = Lists.newArrayList();
        List<PreparedStatement> deleteNeedsRollup = Lists.newArrayList();
        for (int i = 1; i < count; i++) {
            session.execute("create table if not exists aggregate_needs_rollup_" + i
                    + " (agent_rollup varchar, capture_time timestamp, uniqueness timeuuid,"
                    + " transaction_types set<varchar>, primary key (agent_rollup, capture_time,"
                    + " uniqueness)) with gc_grace_seconds = " + needsRollupGcGraceSeconds + " and "
                    + LCS);
            // TTL is used to prevent non-idempotent rolling up of partially expired aggregates
            // (e.g. "needs rollup" record resurrecting due to small gc_grace_seconds)
            insertNeedsRollup.add(session.prepare("insert into aggregate_needs_rollup_" + i
                    + " (agent_rollup, capture_time, uniqueness, transaction_types) values"
                    + " (?, ?, ?, ?) using TTL ?"));
            readNeedsRollup.add(session.prepare("select capture_time, uniqueness, transaction_types"
                    + " from aggregate_needs_rollup_" + i + " where agent_rollup = ?"));
            deleteNeedsRollup.add(session.prepare("delete from aggregate_needs_rollup_" + i
                    + " where agent_rollup = ? and capture_time = ? and uniqueness = ?"));
        }
        this.insertNeedsRollup = insertNeedsRollup;
        this.readNeedsRollup = readNeedsRollup;
        this.deleteNeedsRollup = deleteNeedsRollup;

        session.execute("create table if not exists aggregate_needs_rollup_from_child"
                + " (agent_rollup varchar, capture_time timestamp, uniqueness timeuuid,"
                + " child_agent_rollup varchar, transaction_types set<varchar>,"
                + " primary key (agent_rollup, capture_time, uniqueness))"
                + " with gc_grace_seconds = " + needsRollupGcGraceSeconds + " and " + LCS);
        // TTL is used to prevent non-idempotent rolling up of partially expired aggregates
        // (e.g. "needs rollup" record resurrecting due to small gc_grace_seconds)
        insertNeedsRollupFromChild = session.prepare("insert into aggregate_needs_rollup_from_child"
                + " (agent_rollup, capture_time, uniqueness, child_agent_rollup, transaction_types)"
                + " values (?, ?, ?, ?, ?) using TTL ?");
        readNeedsRollupFromChild = session.prepare("select capture_time, uniqueness,"
                + " child_agent_rollup, transaction_types from aggregate_needs_rollup_from_child"
                + " where agent_rollup = ?");
        deleteNeedsRollupFromChild = session.prepare("delete from aggregate_needs_rollup_from_child"
                + " where agent_rollup = ? and capture_time = ? and uniqueness = ?");
    }

    public void store(String agentId, long captureTime,
            List<OldAggregatesByType> aggregatesByTypeList,
            List<Aggregate.SharedQueryText> initialSharedQueryTexts) throws Exception {
        if (aggregatesByTypeList.isEmpty()) {
            agentDao.updateLastCaptureTime(agentId, captureTime).get();
            return;
        }
        List<String> agentRollupIds = agentDao.readAgentRollupIds(agentId);
        int adjustedTTL = getAdjustedTTL(getTTLs().get(0), captureTime, clock);
        List<ResultSetFuture> futures = Lists.newArrayList();
        List<Aggregate.SharedQueryText> sharedQueryTexts = Lists.newArrayList();
        for (Aggregate.SharedQueryText sharedQueryText : initialSharedQueryTexts) {
            String fullTextSha1 = sharedQueryText.getFullTextSha1();
            if (fullTextSha1.isEmpty()) {
                String fullText = sharedQueryText.getFullText();
                if (fullText.length() > StorageConfig.AGGREGATE_QUERY_TEXT_TRUNCATE) {
                    fullTextSha1 =
                            Hashing.sha1().hashString(fullText, Charsets.UTF_8).toString();
                    futures.addAll(fullQueryTextDao.store(agentId, fullTextSha1, fullText));
                    sharedQueryTexts.add(Aggregate.SharedQueryText.newBuilder()
                            .setTruncatedText(fullText.substring(0,
                                    StorageConfig.AGGREGATE_QUERY_TEXT_TRUNCATE))
                            .setFullTextSha1(fullTextSha1)
                            .build());
                } else {
                    sharedQueryTexts.add(sharedQueryText);
                }
            } else {
                futures.addAll(fullQueryTextDao.updateTTL(agentId, fullTextSha1));
                sharedQueryTexts.add(sharedQueryText);
            }
        }

        // wait for success before proceeding in order to ensure cannot end up with orphaned
        // fullTextSha1
        MoreFutures.waitForAll(futures);
        futures.clear();

        for (OldAggregatesByType aggregatesByType : aggregatesByTypeList) {
            String transactionType = aggregatesByType.getTransactionType();
            Aggregate overallAggregate = aggregatesByType.getOverallAggregate();
            futures.addAll(storeOverallAggregate(agentId, transactionType, captureTime,
                    overallAggregate, sharedQueryTexts, adjustedTTL));
            for (OldTransactionAggregate transactionAggregate : aggregatesByType
                    .getTransactionAggregateList()) {
                futures.addAll(storeTransactionAggregate(agentId, transactionType,
                        transactionAggregate.getTransactionName(), captureTime,
                        transactionAggregate.getAggregate(), sharedQueryTexts, adjustedTTL));
            }
            futures.addAll(transactionTypeDao.store(agentRollupIds, transactionType));
        }
        futures.add(agentDao.updateLastCaptureTime(agentId, captureTime));
        // wait for success before inserting "needs rollup" records
        MoreFutures.waitForAll(futures);
        futures.clear();

        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        // TODO report checker framework issue that occurs without this suppression
        @SuppressWarnings("assignment.type.incompatible")
        Set<String> transactionTypes = aggregatesByTypeList.stream()
                .map(OldAggregatesByType::getTransactionType).collect(Collectors.toSet());

        int needsRollupAdjustedTTL = getNeedsRollupAdjustedTTL(adjustedTTL, rollupConfigs);
        if (agentRollupIds.size() > 1) {
            BoundStatement boundStatement = insertNeedsRollupFromChild.bind();
            int i = 0;
            boundStatement.setString(i++, agentRollupIds.get(1));
            boundStatement.setTimestamp(i++, new Date(captureTime));
            boundStatement.setUUID(i++, UUIDs.timeBased());
            boundStatement.setString(i++, agentId);
            boundStatement.setSet(i++, transactionTypes);
            boundStatement.setInt(i++, needsRollupAdjustedTTL);
            futures.add(session.executeAsync(boundStatement));
        }
        // insert into aggregate_needs_rollup_1
        long intervalMillis = rollupConfigs.get(1).intervalMillis();
        long rollupCaptureTime = Utils.getRollupCaptureTime(captureTime, intervalMillis);
        BoundStatement boundStatement = insertNeedsRollup.get(0).bind();
        int i = 0;
        boundStatement.setString(i++, agentId);
        boundStatement.setTimestamp(i++, new Date(rollupCaptureTime));
        boundStatement.setUUID(i++, UUIDs.timeBased());
        boundStatement.setSet(i++, transactionTypes);
        boundStatement.setInt(i++, needsRollupAdjustedTTL);
        futures.add(session.executeAsync(boundStatement));
        MoreFutures.waitForAll(futures);
    }

    // query.from() is non-inclusive
    @Override
    public void mergeOverallSummaryInto(String agentRollupId, OverallQuery query,
            OverallSummaryCollector collector) {
        // currently have to do aggregation client-site (don't want to require Cassandra 2.2 yet)
        ResultSet results = createBoundStatement(agentRollupId, query, summaryTable);
        for (Row row : results) {
            int i = 0;
            // results are ordered by capture time so Math.max() is not needed here
            long captureTime = checkNotNull(row.getTimestamp(i++)).getTime();
            double totalDurationNanos = row.getDouble(i++);
            long transactionCount = row.getLong(i++);
            collector.mergeSummary(totalDurationNanos, transactionCount, captureTime);
        }
    }

    // sortOrder and limit are only used by embedded H2 repository, while the central cassandra
    // repository which currently has to pull in all records anyways just delegates ordering and
    // limit to TransactionSummaryCollector
    //
    // query.from() is non-inclusive
    @Override
    public void mergeTransactionSummariesInto(String agentRollupId, OverallQuery query,
            SummarySortOrder sortOrder, int limit, TransactionSummaryCollector collector) {
        // currently have to do group by / sort / limit client-side
        BoundStatement boundStatement =
                checkNotNull(readTransactionPS.get(summaryTable)).get(query.rollupLevel()).bind();
        bindQuery(boundStatement, agentRollupId, query);
        ResultSet results = session.execute(boundStatement);
        for (Row row : results) {
            int i = 0;
            long captureTime = checkNotNull(row.getTimestamp(i++)).getTime();
            String transactionName = checkNotNull(row.getString(i++));
            double totalDurationNanos = row.getDouble(i++);
            long transactionCount = row.getLong(i++);
            collector.collect(transactionName, totalDurationNanos, transactionCount, captureTime);
        }
    }

    // query.from() is non-inclusive
    @Override
    public void mergeOverallErrorSummaryInto(String agentRollupId, OverallQuery query,
            OverallErrorSummaryCollector collector) {
        // currently have to do aggregation client-site (don't want to require Cassandra 2.2 yet)
        ResultSet results = createBoundStatement(agentRollupId, query, errorSummaryTable);
        for (Row row : results) {
            int i = 0;
            // results are ordered by capture time so Math.max() is not needed here
            long captureTime = checkNotNull(row.getTimestamp(i++)).getTime();
            long errorCount = row.getLong(i++);
            long transactionCount = row.getLong(i++);
            collector.mergeErrorSummary(errorCount, transactionCount, captureTime);
        }
    }

    // sortOrder and limit are only used by embedded H2 repository, while the central cassandra
    // repository which currently has to pull in all records anyways just delegates ordering and
    // limit to TransactionErrorSummaryCollector
    //
    // query.from() is non-inclusive
    @Override
    public void mergeTransactionErrorSummariesInto(String agentRollupId, OverallQuery query,
            ErrorSummarySortOrder sortOrder, int limit,
            TransactionErrorSummaryCollector collector) {
        // currently have to do group by / sort / limit client-side
        BoundStatement boundStatement = checkNotNull(readTransactionPS.get(errorSummaryTable))
                .get(query.rollupLevel()).bind();
        bindQuery(boundStatement, agentRollupId, query);
        ResultSet results = session.execute(boundStatement);
        for (Row row : results) {
            int i = 0;
            long captureTime = checkNotNull(row.getTimestamp(i++)).getTime();
            String transactionName = checkNotNull(row.getString(i++));
            long errorCount = row.getLong(i++);
            long transactionCount = row.getLong(i++);
            collector.collect(transactionName, errorCount, transactionCount, captureTime);
        }
    }

    // query.from() is INCLUSIVE
    @Override
    public List<OverviewAggregate> readOverviewAggregates(String agentRollupId,
            TransactionQuery query) throws IOException {
        ResultSet results = executeQuery(agentRollupId, query, overviewTable);
        List<OverviewAggregate> overviewAggregates = Lists.newArrayList();
        for (Row row : results) {
            int i = 0;
            long captureTime = checkNotNull(row.getTimestamp(i++)).getTime();
            double totalDurationNanos = row.getDouble(i++);
            long transactionCount = row.getLong(i++);
            boolean asyncTransactions = row.getBool(i++);
            List<Aggregate.Timer> mainThreadRootTimers =
                    Messages.parseDelimitedFrom(row.getBytes(i++), Aggregate.Timer.parser());
            List<Aggregate.Timer> auxThreadRootTimers =
                    Messages.parseDelimitedFrom(row.getBytes(i++), Aggregate.Timer.parser());
            List<Aggregate.Timer> asyncTimers =
                    Messages.parseDelimitedFrom(row.getBytes(i++), Aggregate.Timer.parser());
            ImmutableOverviewAggregate.Builder builder = ImmutableOverviewAggregate.builder()
                    .captureTime(captureTime)
                    .totalDurationNanos(totalDurationNanos)
                    .transactionCount(transactionCount)
                    .asyncTransactions(asyncTransactions)
                    .addAllMainThreadRootTimers(mainThreadRootTimers)
                    .addAllAuxThreadRootTimers(auxThreadRootTimers)
                    .addAllAsyncTimers(asyncTimers);
            Double mainThreadTotalCpuNanos = row.get(i++, Double.class);
            Double mainThreadTotalBlockedNanos = row.get(i++, Double.class);
            Double mainThreadTotalWaitedNanos = row.get(i++, Double.class);
            Double mainThreadTotalAllocatedBytes = row.get(i++, Double.class);
            Aggregate.ThreadStats mainThreadStats =
                    ThreadStatsCreator.create(mainThreadTotalCpuNanos, mainThreadTotalBlockedNanos,
                            mainThreadTotalWaitedNanos, mainThreadTotalAllocatedBytes);
            if (mainThreadStats != null) {
                builder.mainThreadStats(mainThreadStats);
            }
            Double auxThreadTotalCpuNanos = row.get(i++, Double.class);
            Double auxThreadTotalBlockedNanos = row.get(i++, Double.class);
            Double auxThreadTotalWaitedNanos = row.get(i++, Double.class);
            Double auxThreadTotalAllocatedBytes = row.get(i++, Double.class);
            Aggregate.ThreadStats auxThreadStats =
                    ThreadStatsCreator.create(auxThreadTotalCpuNanos, auxThreadTotalBlockedNanos,
                            auxThreadTotalWaitedNanos, auxThreadTotalAllocatedBytes);
            if (auxThreadStats != null) {
                builder.auxThreadStats(auxThreadStats);
            }
            overviewAggregates.add(builder.build());
        }
        return overviewAggregates;
    }

    // query.from() is INCLUSIVE
    @Override
    public List<PercentileAggregate> readPercentileAggregates(String agentRollupId,
            TransactionQuery query) throws InvalidProtocolBufferException {
        ResultSet results = executeQuery(agentRollupId, query, histogramTable);
        List<PercentileAggregate> percentileAggregates = Lists.newArrayList();
        for (Row row : results) {
            int i = 0;
            long captureTime = checkNotNull(row.getTimestamp(i++)).getTime();
            double totalDurationNanos = row.getDouble(i++);
            long transactionCount = row.getLong(i++);
            ByteBuffer bytes = checkNotNull(row.getBytes(i++));
            Aggregate.Histogram durationNanosHistogram =
                    Aggregate.Histogram.parseFrom(ByteString.copyFrom(bytes));
            percentileAggregates.add(ImmutablePercentileAggregate.builder()
                    .captureTime(captureTime)
                    .totalDurationNanos(totalDurationNanos)
                    .transactionCount(transactionCount)
                    .durationNanosHistogram(durationNanosHistogram)
                    .build());
        }
        return percentileAggregates;
    }

    // query.from() is INCLUSIVE
    @Override
    public List<ThroughputAggregate> readThroughputAggregates(String agentRollupId,
            TransactionQuery query) throws IOException {
        ResultSet results = executeQuery(agentRollupId, query, throughputTable);
        List<ThroughputAggregate> throughputAggregates = Lists.newArrayList();
        for (Row row : results) {
            long captureTime = checkNotNull(row.getTimestamp(0)).getTime();
            long transactionCount = row.getLong(1);
            throughputAggregates.add(ImmutableThroughputAggregate.builder()
                    .captureTime(captureTime)
                    .transactionCount(transactionCount)
                    .build());
        }
        return throughputAggregates;
    }

    @Override
    public @Nullable String readFullQueryText(String agentRollupId, String fullQueryTextSha1)
            throws Exception {
        return fullQueryTextDao.getFullText(agentRollupId, fullQueryTextSha1);
    }

    // query.from() is non-inclusive
    @Override
    public void mergeQueriesInto(String agentRollupId, TransactionQuery query,
            QueryCollector collector) throws IOException {
        ResultSet results = executeQuery(agentRollupId, query, queryTable);
        long captureTime = Long.MIN_VALUE;
        for (Row row : results) {
            int i = 0;
            captureTime = Math.max(captureTime, checkNotNull(row.getTimestamp(i++)).getTime());
            String queryType = checkNotNull(row.getString(i++));
            String truncatedText = checkNotNull(row.getString(i++));
            // full_query_text_sha1 cannot be null since it is used in clustering key
            String fullTextSha1 = Strings.emptyToNull(row.getString(i++));
            double totalDurationNanos = row.getDouble(i++);
            long executionCount = row.getLong(i++);
            boolean hasTotalRows = !row.isNull(i);
            long totalRows = row.getLong(i++);
            collector.mergeQuery(queryType, truncatedText, fullTextSha1, totalDurationNanos,
                    executionCount, hasTotalRows, totalRows);
            collector.updateLastCaptureTime(captureTime);
        }
    }

    // query.from() is non-inclusive
    @Override
    public void mergeServiceCallsInto(String agentRollupId, TransactionQuery query,
            ServiceCallCollector collector) throws IOException {
        ResultSet results = executeQuery(agentRollupId, query, serviceCallTable);
        long captureTime = Long.MIN_VALUE;
        for (Row row : results) {
            int i = 0;
            captureTime = Math.max(captureTime, checkNotNull(row.getTimestamp(i++)).getTime());
            String serviceCallType = checkNotNull(row.getString(i++));
            String serviceCallText = checkNotNull(row.getString(i++));
            double totalDurationNanos = row.getDouble(i++);
            long executionCount = row.getLong(i++);
            collector.mergeServiceCall(serviceCallType, serviceCallText, totalDurationNanos,
                    executionCount);
            collector.updateLastCaptureTime(captureTime);
        }
    }

    // query.from() is non-inclusive
    @Override
    public void mergeMainThreadProfilesInto(String agentRollupId, TransactionQuery query,
            ProfileCollector collector) throws InvalidProtocolBufferException {
        mergeProfilesInto(agentRollupId, query, mainThreadProfileTable, collector);
    }

    // query.from() is non-inclusive
    @Override
    public void mergeAuxThreadProfilesInto(String agentRollupId, TransactionQuery query,
            ProfileCollector collector) throws InvalidProtocolBufferException {
        mergeProfilesInto(agentRollupId, query, auxThreadProfileTable, collector);
    }

    // query.from() is non-inclusive
    @Override
    public boolean hasMainThreadProfile(String agentRollupId, TransactionQuery query)
            throws Exception {
        BoundStatement boundStatement = query.transactionName() == null
                ? existsMainThreadProfileOverallPS.get(query.rollupLevel()).bind()
                : existsMainThreadProfileTransactionPS.get(query.rollupLevel()).bind();
        bindQuery(boundStatement, agentRollupId, query);
        ResultSet results = session.execute(boundStatement);
        return results.one() != null;
    }

    // query.from() is non-inclusive
    @Override
    public boolean hasAuxThreadProfile(String agentRollupId, TransactionQuery query)
            throws Exception {
        BoundStatement boundStatement = query.transactionName() == null
                ? existsAuxThreadProfileOverallPS.get(query.rollupLevel()).bind()
                : existsAuxThreadProfileTransactionPS.get(query.rollupLevel()).bind();
        bindQuery(boundStatement, agentRollupId, query);
        ResultSet results = session.execute(boundStatement);
        return results.one() != null;
    }

    // query.from() is non-inclusive
    @Override
    public boolean shouldHaveQueries(String agentRollupId, TransactionQuery query) {
        // TODO (this is only used for displaying data expired message)
        return false;
    }

    // query.from() is non-inclusive
    @Override
    public boolean shouldHaveServiceCalls(String agentRollupId, TransactionQuery query) {
        // TODO (this is only used for displaying data expired message)
        return false;
    }

    // query.from() is non-inclusive
    @Override
    public boolean shouldHaveMainThreadProfile(String agentRollupId, TransactionQuery query) {
        // TODO (this is only used for displaying data expired message)
        return false;
    }

    // query.from() is non-inclusive
    @Override
    public boolean shouldHaveAuxThreadProfile(String agentRollupId, TransactionQuery query) {
        // TODO (this is only used for displaying data expired message)
        return false;
    }

    @OnlyUsedByTests
    void truncateAll() {
        for (Table table : allTables) {
            for (int i = 0; i < configRepository.getRollupConfigs().size(); i++) {
                session.execute("truncate " + getTableName(table.partialName(), false, i));
                session.execute("truncate " + getTableName(table.partialName(), true, i));
            }
        }
        for (int i = 1; i < configRepository.getRollupConfigs().size(); i++) {
            session.execute("truncate aggregate_needs_rollup_" + i);
        }
        session.execute("truncate aggregate_needs_rollup_from_child");
    }

    @Instrumentation.Transaction(transactionType = "Background",
            transactionName = "Rollup aggregates", traceHeadline = "Rollup aggregates: {{0}}",
            timer = "rollup aggregates")
    public void rollup(String agentRollupId, @Nullable String parentAgentRollupId, boolean leaf)
            throws Exception {
        List<Integer> ttls = getTTLs();
        if (!leaf) {
            rollupFromChildren(agentRollupId, parentAgentRollupId, ttls.get(0));
        }
        int rollupLevel = 1;
        while (rollupLevel < configRepository.getRollupConfigs().size()) {
            int ttl = ttls.get(rollupLevel);
            rollup(agentRollupId, rollupLevel, ttl);
            rollupLevel++;
        }
    }

    private void rollupFromChildren(String agentRollupId, @Nullable String parentAgentRollupId,
            int ttl) throws Exception {
        final int rollupLevel = 0;
        List<NeedsRollupFromChildren> needsRollupFromChildrenList =
                getNeedsRollupFromChildrenList(agentRollupId, readNeedsRollupFromChild, session);
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        long nextRollupIntervalMillis = rollupConfigs.get(rollupLevel + 1).intervalMillis();
        for (NeedsRollupFromChildren needsRollupFromChildren : needsRollupFromChildrenList) {
            long captureTime = needsRollupFromChildren.getCaptureTime();
            int adjustedTTL = getAdjustedTTL(ttl, captureTime, clock);
            int needsRollupAdjustedTTL = getNeedsRollupAdjustedTTL(adjustedTTL, rollupConfigs);
            RollupParams rollupParams = getRollupParams(agentRollupId, rollupLevel, adjustedTTL);
            List<ResultSetFuture> futures = Lists.newArrayList();
            for (Entry<String, Collection<String>> entry : needsRollupFromChildren.getKeys().asMap()
                    .entrySet()) {
                String transactionType = entry.getKey();
                Collection<String> childAgentRollups = entry.getValue();
                futures.addAll(rollupOneFromChildren(rollupParams, transactionType,
                        childAgentRollups, captureTime));
            }
            // wait for above async work to ensure rollup complete before proceeding
            MoreFutures.waitForAll(futures);

            if (parentAgentRollupId != null) {
                // insert needs to happen first before call to postRollup(), see method-level
                // comment on postRollup
                BoundStatement boundStatement = insertNeedsRollupFromChild.bind();
                int i = 0;
                boundStatement.setString(i++, parentAgentRollupId);
                boundStatement.setTimestamp(i++, new Date(captureTime));
                boundStatement.setUUID(i++, UUIDs.timeBased());
                boundStatement.setString(i++, agentRollupId);
                boundStatement.setSet(i++, needsRollupFromChildren.getKeys().keySet());
                boundStatement.setInt(i++, needsRollupAdjustedTTL);
                session.execute(boundStatement);
            }
            postRollup(agentRollupId, needsRollupFromChildren.getCaptureTime(),
                    needsRollupFromChildren.getKeys().keySet(),
                    needsRollupFromChildren.getUniquenessKeysForDeletion(),
                    nextRollupIntervalMillis, insertNeedsRollup.get(rollupLevel),
                    deleteNeedsRollupFromChild, needsRollupAdjustedTTL, session);
        }
    }

    private void rollup(String agentRollupId, int rollupLevel, int ttl) throws Exception {
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        long rollupIntervalMillis = rollupConfigs.get(rollupLevel).intervalMillis();
        List<NeedsRollup> needsRollupList = getNeedsRollupList(agentRollupId, rollupLevel,
                rollupIntervalMillis, readNeedsRollup, session, clock);
        Long nextRollupIntervalMillis = null;
        if (rollupLevel + 1 < rollupConfigs.size()) {
            nextRollupIntervalMillis = rollupConfigs.get(rollupLevel + 1).intervalMillis();
        }
        for (NeedsRollup needsRollup : needsRollupList) {
            long captureTime = needsRollup.getCaptureTime();
            int adjustedTTL = getAdjustedTTL(ttl, captureTime, clock);
            int needsRollupAdjustedTTL = getNeedsRollupAdjustedTTL(adjustedTTL, rollupConfigs);
            RollupParams rollupParams = getRollupParams(agentRollupId, rollupLevel, adjustedTTL);
            long from = captureTime - rollupIntervalMillis;
            Set<String> transactionTypes = needsRollup.getKeys();
            List<ResultSetFuture> futures = Lists.newArrayList();
            for (String transactionType : transactionTypes) {
                futures.addAll(rollupOne(rollupParams, transactionType, from, captureTime));
            }
            if (futures.isEmpty()) {
                // no rollups occurred, warning already logged inside rollupOne() above
                // this can happen there is an old "needs rollup" record that was created prior to
                // TTL was introduced in 0.9.6, and when the "last needs rollup" record wasn't
                // processed (also prior to 0.9.6), and when the corresponding old data has expired
                AggregateDao.postRollup(agentRollupId, needsRollup.getCaptureTime(),
                        transactionTypes, needsRollup.getUniquenessKeysForDeletion(), null, null,
                        deleteNeedsRollup.get(rollupLevel - 1), -1, session);
                continue;
            }
            // wait for above async work to ensure rollup complete before proceeding
            MoreFutures.waitForAll(futures);

            PreparedStatement insertNeedsRollup = nextRollupIntervalMillis == null ? null
                    : this.insertNeedsRollup.get(rollupLevel);
            PreparedStatement deleteNeedsRollup = this.deleteNeedsRollup.get(rollupLevel - 1);
            postRollup(agentRollupId, needsRollup.getCaptureTime(), transactionTypes,
                    needsRollup.getUniquenessKeysForDeletion(), nextRollupIntervalMillis,
                    insertNeedsRollup, deleteNeedsRollup, needsRollupAdjustedTTL, session);
        }
    }

    private List<ResultSetFuture> rollupOneFromChildren(RollupParams rollup, String transactionType,
            Collection<String> childAgentRollups, long captureTime) throws Exception {

        ImmutableTransactionQuery query = ImmutableTransactionQuery.builder()
                .transactionType(transactionType)
                .from(captureTime)
                .to(captureTime)
                .rollupLevel(rollup.rollupLevel()) // rolling up from same level (which is always 0)
                .build();
        List<ResultSetFuture> futures = Lists.newArrayList();

        futures.addAll(rollupOverallSummaryFromChildren(rollup, query, childAgentRollups));
        futures.addAll(rollupErrorSummaryFromChildren(rollup, query, childAgentRollups));

        List<String> transactionNames = Lists.newArrayList();
        futures.addAll(rollupTransactionSummaryFromChildren(rollup, query, childAgentRollups,
                transactionNames));
        futures.addAll(rollupTransactionErrorSummaryFromChildren(rollup, query, childAgentRollups));

        ScratchBuffer scratchBuffer = new ScratchBuffer();
        futures.addAll(
                rollupOtherPartsFromChildren(rollup, query, childAgentRollups, scratchBuffer));

        for (String transactionName : transactionNames) {
            futures.addAll(rollupOtherPartsFromChildren(rollup,
                    query.withTransactionName(transactionName), childAgentRollups, scratchBuffer));
        }
        return futures;
    }

    private List<ResultSetFuture> rollupOne(RollupParams rollup, String transactionType, long from,
            long to) throws Exception {

        ImmutableTransactionQuery query = ImmutableTransactionQuery.builder()
                .transactionType(transactionType)
                .from(from)
                .to(to)
                .rollupLevel(rollup.rollupLevel() - 1)
                .build();
        List<ResultSetFuture> futures = Lists.newArrayList();

        futures.addAll(rollupOverallSummary(rollup, query));
        futures.addAll(rollupErrorSummary(rollup, query));

        List<String> transactionNames = Lists.newArrayList();
        futures.addAll(rollupTransactionSummary(rollup, query, transactionNames));
        futures.addAll(rollupTransactionErrorSummary(rollup, query));

        ScratchBuffer scratchBuffer = new ScratchBuffer();
        futures.addAll(rollupOtherParts(rollup, query, scratchBuffer));

        for (String transactionName : transactionNames) {
            futures.addAll(rollupOtherParts(rollup, query.withTransactionName(transactionName),
                    scratchBuffer));
        }
        return futures;
    }

    private List<ResultSetFuture> rollupOtherParts(RollupParams rollup, TransactionQuery query,
            ScratchBuffer scratchBuffer) throws Exception {
        List<ResultSetFuture> futures = Lists.newArrayList();
        futures.addAll(rollupOverview(rollup, query));
        futures.addAll(rollupHistogram(rollup, query, scratchBuffer));
        futures.addAll(rollupThroughput(rollup, query));
        futures.addAll(rollupQueries(rollup, query));
        futures.addAll(rollupServiceCalls(rollup, query));
        futures.addAll(rollupThreadProfile(rollup, query, mainThreadProfileTable));
        futures.addAll(rollupThreadProfile(rollup, query, auxThreadProfileTable));
        return futures;
    }

    private List<ResultSetFuture> rollupOtherPartsFromChildren(RollupParams rollup,
            TransactionQuery query, Collection<String> childAgentRollups,
            ScratchBuffer scratchBuffer)
            throws Exception {
        List<ResultSetFuture> futures = Lists.newArrayList();
        futures.addAll(rollupOverviewFromChildren(rollup, query, childAgentRollups));
        futures.addAll(
                rollupHistogramFromChildren(rollup, query, childAgentRollups, scratchBuffer));
        futures.addAll(rollupThroughputFromChildren(rollup, query, childAgentRollups));
        futures.addAll(rollupQueriesFromChildren(rollup, query, childAgentRollups));
        futures.addAll(rollupServiceCallsFromChildren(rollup, query, childAgentRollups));
        futures.addAll(rollupThreadProfileFromChildren(rollup, query, childAgentRollups,
                mainThreadProfileTable));
        futures.addAll(rollupThreadProfileFromChildren(rollup, query, childAgentRollups,
                auxThreadProfileTable));
        return futures;
    }

    private List<ResultSetFuture> rollupOverallSummary(RollupParams rollup,
            TransactionQuery query) {
        ResultSet results = executeQueryForRollup(rollup.agentRollupId(), query, summaryTable);
        if (results.isExhausted()) {
            // this is unexpected since TTL for "needs rollup" records is shorter than TTL for data
            logger.warn("no summary table records found for agentRollupId={}, query={}",
                    rollup.agentRollupId(), query);
            return ImmutableList.of();
        }
        return rollupOverallSummaryFromRows(rollup, query, results);
    }

    private List<ResultSetFuture> rollupOverallSummaryFromChildren(RollupParams rollup,
            TransactionQuery query, Collection<String> childAgentRollups) {
        List<Row> rows = getRowsForRollupFromChildren(query, childAgentRollups, summaryTable);
        if (rows.isEmpty()) {
            // this is unexpected since TTL for "needs rollup" records is shorter than TTL for data
            logger.warn("no summary table records found for agentRollupId={}, query={}",
                    rollup.agentRollupId(), query);
            return ImmutableList.of();
        }
        return rollupOverallSummaryFromRows(rollup, query, rows);
    }

    private List<ResultSetFuture> rollupOverallSummaryFromRows(RollupParams rollup,
            TransactionQuery query, Iterable<Row> rows) {
        double totalDurationNanos = 0;
        long transactionCount = 0;
        for (Row row : rows) {
            totalDurationNanos += row.getDouble(0);
            transactionCount += row.getLong(1);
        }
        BoundStatement boundStatement =
                getInsertOverallPS(summaryTable, rollup.rollupLevel()).bind();
        int i = 0;
        boundStatement.setString(i++, rollup.agentRollupId());
        boundStatement.setString(i++, query.transactionType());
        boundStatement.setTimestamp(i++, new Date(query.to()));
        boundStatement.setDouble(i++, totalDurationNanos);
        boundStatement.setLong(i++, transactionCount);
        boundStatement.setInt(i++, rollup.adjustedTTL());
        return ImmutableList.of(session.executeAsync(boundStatement));
    }

    private List<ResultSetFuture> rollupErrorSummary(RollupParams rollup, TransactionQuery query) {
        ResultSet results = executeQueryForRollup(rollup.agentRollupId(), query, errorSummaryTable);
        if (results.isExhausted()) {
            return ImmutableList.of();
        }
        return rollupErrorSummaryFromRows(rollup, query, results);
    }

    private List<ResultSetFuture> rollupErrorSummaryFromChildren(RollupParams rollup,
            TransactionQuery query, Collection<String> childAgentRollups) {
        List<Row> rows = getRowsForRollupFromChildren(query, childAgentRollups, errorSummaryTable);
        if (rows.isEmpty()) {
            return ImmutableList.of();
        }
        return rollupErrorSummaryFromRows(rollup, query, rows);
    }

    private List<ResultSetFuture> rollupErrorSummaryFromRows(RollupParams rollup,
            TransactionQuery query, Iterable<Row> rows) {
        long errorCount = 0;
        long transactionCount = 0;
        for (Row row : rows) {
            errorCount += row.getLong(0);
            transactionCount += row.getLong(1);
        }
        BoundStatement boundStatement =
                getInsertOverallPS(errorSummaryTable, rollup.rollupLevel()).bind();
        int i = 0;
        boundStatement.setString(i++, rollup.agentRollupId());
        boundStatement.setString(i++, query.transactionType());
        boundStatement.setTimestamp(i++, new Date(query.to()));
        boundStatement.setLong(i++, errorCount);
        boundStatement.setLong(i++, transactionCount);
        boundStatement.setInt(i++, rollup.adjustedTTL());
        return ImmutableList.of(session.executeAsync(boundStatement));
    }

    // transactionNames is passed in empty, and populated by method
    private List<ResultSetFuture> rollupTransactionSummary(RollupParams rollup,
            TransactionQuery query, List<String> transactionNames) {
        BoundStatement boundStatement = checkNotNull(readTransactionForRollupPS.get(summaryTable))
                .get(query.rollupLevel()).bind();
        bindQuery(boundStatement, rollup.agentRollupId(), query);
        ResultSet results = session.execute(boundStatement);
        if (results.isExhausted()) {
            // this is unexpected since TTL for "needs rollup" records is shorter than TTL for data
            logger.warn("no summary table records found for agentRollupId={}, query={}",
                    rollup.agentRollupId(), query);
            return ImmutableList.of();
        }
        return rollupTransactionSummaryFromRows(rollup, query, results, transactionNames);
    }

    // transactionNames is passed in empty, and populated by method
    private List<ResultSetFuture> rollupTransactionSummaryFromChildren(RollupParams rollup,
            TransactionQuery query, Collection<String> childAgentRollups,
            List<String> transactionNames) {
        List<Row> rows =
                getRowsForSummaryRollupFromChildren(query, childAgentRollups, summaryTable);
        if (rows.isEmpty()) {
            // this is unexpected since TTL for "needs rollup" records is shorter than TTL for data
            logger.warn("no summary table records found for agentRollupId={}, query={}",
                    rollup.agentRollupId(), query);
            return ImmutableList.of();
        }
        return rollupTransactionSummaryFromRows(rollup, query, rows, transactionNames);
    }

    // transactionNames is passed in empty, and populated by method
    private List<ResultSetFuture> rollupTransactionSummaryFromRows(RollupParams rollup,
            TransactionQuery query, Iterable<Row> rows, List<String> transactionNames) {
        BoundStatement boundStatement;
        Map<String, MutableSummary> summaries = Maps.newHashMap();
        for (Row row : rows) {
            int i = 0;
            String transactionName = checkNotNull(row.getString(i++));
            MutableSummary summary = summaries.get(transactionName);
            if (summary == null) {
                summary = new MutableSummary();
                summaries.put(transactionName, summary);
            }
            summary.totalDurationNanos += row.getDouble(i++);
            summary.transactionCount += row.getLong(i++);
        }
        List<ResultSetFuture> futures = Lists.newArrayList();
        PreparedStatement preparedStatement =
                getInsertTransactionPS(summaryTable, rollup.rollupLevel());
        for (Entry<String, MutableSummary> entry : summaries.entrySet()) {
            MutableSummary summary = entry.getValue();
            boundStatement = preparedStatement.bind();
            int i = 0;
            boundStatement.setString(i++, rollup.agentRollupId());
            boundStatement.setString(i++, query.transactionType());
            boundStatement.setTimestamp(i++, new Date(query.to()));
            boundStatement.setString(i++, entry.getKey());
            boundStatement.setDouble(i++, summary.totalDurationNanos);
            boundStatement.setLong(i++, summary.transactionCount);
            boundStatement.setInt(i++, rollup.adjustedTTL());
            futures.add(session.executeAsync(boundStatement));
        }
        transactionNames.addAll(summaries.keySet());
        return futures;
    }

    private List<ResultSetFuture> rollupTransactionErrorSummary(RollupParams rollup,
            TransactionQuery query) {
        BoundStatement boundStatement =
                checkNotNull(readTransactionForRollupPS.get(errorSummaryTable))
                        .get(query.rollupLevel()).bind();
        bindQuery(boundStatement, rollup.agentRollupId(), query);
        ResultSet results = session.execute(boundStatement);
        if (results.isExhausted()) {
            return ImmutableList.of();
        }
        return rollupTransactionErrorSummaryFromRows(rollup, query, results);
    }

    // transactionNames is passed in empty, and populated by method
    private List<ResultSetFuture> rollupTransactionErrorSummaryFromChildren(RollupParams rollup,
            TransactionQuery query, Collection<String> childAgentRollups) {
        List<Row> rows =
                getRowsForSummaryRollupFromChildren(query, childAgentRollups, errorSummaryTable);
        if (rows.isEmpty()) {
            return ImmutableList.of();
        }
        return rollupTransactionErrorSummaryFromRows(rollup, query, rows);
    }

    private List<ResultSetFuture> rollupTransactionErrorSummaryFromRows(RollupParams rollup,
            TransactionQuery query, Iterable<Row> rows) {
        BoundStatement boundStatement;
        Map<String, MutableErrorSummary> summaries = Maps.newHashMap();
        for (Row row : rows) {
            int i = 0;
            String transactionName = checkNotNull(row.getString(i++));
            MutableErrorSummary summary = summaries.get(transactionName);
            if (summary == null) {
                summary = new MutableErrorSummary();
                summaries.put(transactionName, summary);
            }
            summary.errorCount += row.getLong(i++);
            summary.transactionCount += row.getLong(i++);
        }
        PreparedStatement preparedStatement =
                getInsertTransactionPS(errorSummaryTable, rollup.rollupLevel());
        List<ResultSetFuture> futures = Lists.newArrayList();
        for (Entry<String, MutableErrorSummary> entry : summaries.entrySet()) {
            MutableErrorSummary summary = entry.getValue();
            boundStatement = preparedStatement.bind();
            int i = 0;
            boundStatement.setString(i++, rollup.agentRollupId());
            boundStatement.setString(i++, query.transactionType());
            boundStatement.setTimestamp(i++, new Date(query.to()));
            boundStatement.setString(i++, entry.getKey());
            boundStatement.setLong(i++, summary.errorCount);
            boundStatement.setLong(i++, summary.transactionCount);
            boundStatement.setInt(i++, rollup.adjustedTTL());
            futures.add(session.executeAsync(boundStatement));
        }
        return futures;
    }

    private List<ResultSetFuture> rollupOverview(RollupParams rollup, TransactionQuery query)
            throws IOException {
        ResultSet results = executeQueryForRollup(rollup.agentRollupId(), query, overviewTable);
        if (results.isExhausted()) {
            // this is unexpected since TTL for "needs rollup" records is shorter than TTL for data
            logger.warn("no overview table records found for agentRollupId={}, query={}",
                    rollup.agentRollupId(), query);
            return ImmutableList.of();
        }
        return rollupOverviewFromRows(rollup, query, results);
    }

    private List<ResultSetFuture> rollupOverviewFromChildren(RollupParams rollup,
            TransactionQuery query, Collection<String> childAgentRollups) throws IOException {
        List<Row> rows = getRowsForRollupFromChildren(query, childAgentRollups, overviewTable);
        if (rows.isEmpty()) {
            // this is unexpected since TTL for "needs rollup" records is shorter than TTL for data
            logger.warn("no overview table records found for agentRollupId={}, query={}",
                    rollup.agentRollupId(), query);
            return ImmutableList.of();
        }
        return rollupOverviewFromRows(rollup, query, rows);
    }

    private List<ResultSetFuture> rollupOverviewFromRows(RollupParams rollup,
            TransactionQuery query, Iterable<Row> rows) throws IOException {
        double totalDurationNanos = 0;
        long transactionCount = 0;
        boolean asyncTransactions = false;
        List<MutableTimer> mainThreadRootTimers = Lists.newArrayList();
        List<MutableTimer> auxThreadRootTimers = Lists.newArrayList();
        List<MutableTimer> asyncTimers = Lists.newArrayList();
        MutableThreadStats mainThreadStats = new MutableThreadStats();
        MutableThreadStats auxThreadStats = new MutableThreadStats();
        for (Row row : rows) {
            int i = 0;
            totalDurationNanos += row.getDouble(i++);
            transactionCount += row.getLong(i++);
            if (row.getBool(i++)) {
                asyncTransactions = true;
            }
            List<Aggregate.Timer> toBeMergedMainThreadRootTimers =
                    Messages.parseDelimitedFrom(row.getBytes(i++), Aggregate.Timer.parser());
            MutableAggregate.mergeRootTimers(toBeMergedMainThreadRootTimers, mainThreadRootTimers);
            List<Aggregate.Timer> toBeMergedAuxThreadRootTimers =
                    Messages.parseDelimitedFrom(row.getBytes(i++), Aggregate.Timer.parser());
            MutableAggregate.mergeRootTimers(toBeMergedAuxThreadRootTimers, auxThreadRootTimers);
            List<Aggregate.Timer> toBeMergedAsyncTimers =
                    Messages.parseDelimitedFrom(row.getBytes(i++), Aggregate.Timer.parser());
            MutableAggregate.mergeRootTimers(toBeMergedAsyncTimers, asyncTimers);
            mainThreadStats.addTotalCpuNanos(row.get(i++, Double.class));
            mainThreadStats.addTotalBlockedNanos(row.get(i++, Double.class));
            mainThreadStats.addTotalWaitedNanos(row.get(i++, Double.class));
            mainThreadStats.addTotalAllocatedBytes(row.get(i++, Double.class));
            auxThreadStats.addTotalCpuNanos(row.get(i++, Double.class));
            auxThreadStats.addTotalBlockedNanos(row.get(i++, Double.class));
            auxThreadStats.addTotalWaitedNanos(row.get(i++, Double.class));
            auxThreadStats.addTotalAllocatedBytes(row.get(i++, Double.class));
        }
        BoundStatement boundStatement;
        if (query.transactionName() == null) {
            boundStatement = getInsertOverallPS(overviewTable, rollup.rollupLevel()).bind();
        } else {
            boundStatement = getInsertTransactionPS(overviewTable, rollup.rollupLevel()).bind();
        }
        int i = 0;
        boundStatement.setString(i++, rollup.agentRollupId());
        boundStatement.setString(i++, query.transactionType());
        if (query.transactionName() != null) {
            boundStatement.setString(i++, query.transactionName());
        }
        boundStatement.setTimestamp(i++, new Date(query.to()));
        boundStatement.setDouble(i++, totalDurationNanos);
        boundStatement.setLong(i++, transactionCount);
        boundStatement.setBool(i++, asyncTransactions);
        boundStatement.setBytes(i++,
                Messages.toByteBuffer(MutableAggregate.toProto(mainThreadRootTimers)));
        boundStatement.setBytes(i++,
                Messages.toByteBuffer(MutableAggregate.toProto(auxThreadRootTimers)));
        boundStatement.setBytes(i++,
                Messages.toByteBuffer(MutableAggregate.toProto(asyncTimers)));
        boundStatement.setDouble(i++, mainThreadStats.getTotalCpuNanos());
        boundStatement.setDouble(i++, mainThreadStats.getTotalBlockedNanos());
        boundStatement.setDouble(i++, mainThreadStats.getTotalWaitedNanos());
        boundStatement.setDouble(i++, mainThreadStats.getTotalAllocatedBytes());
        boundStatement.setDouble(i++, auxThreadStats.getTotalCpuNanos());
        boundStatement.setDouble(i++, auxThreadStats.getTotalBlockedNanos());
        boundStatement.setDouble(i++, auxThreadStats.getTotalWaitedNanos());
        boundStatement.setDouble(i++, auxThreadStats.getTotalAllocatedBytes());
        boundStatement.setInt(i++, rollup.adjustedTTL());
        return ImmutableList.of(session.executeAsync(boundStatement));
    }

    private List<ResultSetFuture> rollupHistogram(RollupParams rollup, TransactionQuery query,
            ScratchBuffer scratchBuffer) throws Exception {
        ResultSet results = executeQueryForRollup(rollup.agentRollupId(), query, histogramTable);
        if (results.isExhausted()) {
            // this is unexpected since TTL for "needs rollup" records is shorter than TTL for data
            logger.warn("no histogram table records found for agentRollupId={}, query={}",
                    rollup.agentRollupId(), query);
            return ImmutableList.of();
        }
        return rollupHistogramFromRows(rollup, query, results, scratchBuffer);
    }

    private List<ResultSetFuture> rollupHistogramFromChildren(RollupParams rollup,
            TransactionQuery query, Collection<String> childAgentRollups,
            ScratchBuffer scratchBuffer)
            throws Exception {
        List<Row> rows = getRowsForRollupFromChildren(query, childAgentRollups, histogramTable);
        if (rows.isEmpty()) {
            // this is unexpected since TTL for "needs rollup" records is shorter than TTL for data
            logger.warn("no histogram table records found for agentRollupId={}, query={}",
                    rollup.agentRollupId(), query);
            return ImmutableList.of();
        }
        return rollupHistogramFromRows(rollup, query, rows, scratchBuffer);
    }

    private List<ResultSetFuture> rollupHistogramFromRows(RollupParams rollup,
            TransactionQuery query, Iterable<Row> rows, ScratchBuffer scratchBuffer)
            throws Exception {
        double totalDurationNanos = 0;
        long transactionCount = 0;
        LazyHistogram durationNanosHistogram = new LazyHistogram();
        for (Row row : rows) {
            int i = 0;
            totalDurationNanos += row.getDouble(i++);
            transactionCount += row.getLong(i++);
            ByteBuffer bytes = checkNotNull(row.getBytes(i++));
            durationNanosHistogram.merge(Aggregate.Histogram.parseFrom(ByteString.copyFrom(bytes)));
        }
        BoundStatement boundStatement;
        if (query.transactionName() == null) {
            boundStatement = getInsertOverallPS(histogramTable, rollup.rollupLevel()).bind();
        } else {
            boundStatement = getInsertTransactionPS(histogramTable, rollup.rollupLevel()).bind();
        }
        int i = 0;
        boundStatement.setString(i++, rollup.agentRollupId());
        boundStatement.setString(i++, query.transactionType());
        if (query.transactionName() != null) {
            boundStatement.setString(i++, query.transactionName());
        }
        boundStatement.setTimestamp(i++, new Date(query.to()));
        boundStatement.setDouble(i++, totalDurationNanos);
        boundStatement.setLong(i++, transactionCount);
        boundStatement.setBytes(i++, toByteBuffer(durationNanosHistogram.toProto(scratchBuffer)));
        boundStatement.setInt(i++, rollup.adjustedTTL());
        return ImmutableList.of(session.executeAsync(boundStatement));
    }

    private List<ResultSetFuture> rollupThroughput(RollupParams rollup, TransactionQuery query) {
        ResultSet results = executeQueryForRollup(rollup.agentRollupId(), query, throughputTable);
        if (results.isExhausted()) {
            // this is unexpected since TTL for "needs rollup" records is shorter than TTL for data
            logger.warn("no throughput table records found for agentRollupId={}, query={}",
                    rollup.agentRollupId(), query);
            return ImmutableList.of();
        }
        return rollupThroughputFromRows(rollup, query, results);
    }

    private List<ResultSetFuture> rollupThroughputFromChildren(RollupParams rollup,
            TransactionQuery query, Collection<String> childAgentRollups) {
        List<Row> rows = getRowsForRollupFromChildren(query, childAgentRollups, throughputTable);
        if (rows.isEmpty()) {
            // this is unexpected since TTL for "needs rollup" records is shorter than TTL for data
            logger.warn("no throughput table records found for agentRollupId={}, query={}",
                    rollup.agentRollupId(), query);
            return ImmutableList.of();
        }
        return rollupThroughputFromRows(rollup, query, rows);
    }

    private List<ResultSetFuture> rollupThroughputFromRows(RollupParams rollup,
            TransactionQuery query, Iterable<Row> rows) {
        long transactionCount = 0;
        for (Row row : rows) {
            transactionCount += row.getLong(0);
        }
        BoundStatement boundStatement;
        if (query.transactionName() == null) {
            boundStatement = getInsertOverallPS(throughputTable, rollup.rollupLevel()).bind();
        } else {
            boundStatement = getInsertTransactionPS(throughputTable, rollup.rollupLevel()).bind();
        }
        int i = 0;
        boundStatement.setString(i++, rollup.agentRollupId());
        boundStatement.setString(i++, query.transactionType());
        if (query.transactionName() != null) {
            boundStatement.setString(i++, query.transactionName());
        }
        boundStatement.setTimestamp(i++, new Date(query.to()));
        boundStatement.setLong(i++, transactionCount);
        boundStatement.setInt(i++, rollup.adjustedTTL());
        return ImmutableList.of(session.executeAsync(boundStatement));
    }

    private List<ResultSetFuture> rollupQueries(RollupParams rollup, TransactionQuery query)
            throws Exception {
        ResultSet results = executeQueryForRollup(rollup.agentRollupId(), query, queryTable);
        if (results.isExhausted()) {
            return ImmutableList.of();
        }
        return rollupQueriesFromRows(rollup, query, results, false);
    }

    private List<ResultSetFuture> rollupQueriesFromChildren(RollupParams rollup,
            TransactionQuery query, Collection<String> childAgentRollups) throws Exception {
        List<Row> rows = getRowsForRollupFromChildren(query, childAgentRollups, queryTable);
        if (rows.isEmpty()) {
            return ImmutableList.of();
        }
        return rollupQueriesFromRows(rollup, query, rows, true);
    }

    private List<ResultSetFuture> rollupQueriesFromRows(RollupParams rollup, TransactionQuery query,
            Iterable<Row> rows, boolean rollupFromChildren) throws Exception {
        QueryCollector collector = new QueryCollector(rollup.maxAggregateQueriesPerType());
        for (Row row : rows) {
            int i = 0;
            String queryType = checkNotNull(row.getString(i++));
            String truncatedText = checkNotNull(row.getString(i++));
            // full_query_text_sha1 cannot be null since it is used in clustering key
            String fullTextSha1 = Strings.emptyToNull(row.getString(i++));
            double totalDurationNanos = row.getDouble(i++);
            long executionCount = row.getLong(i++);
            boolean hasTotalRows = !row.isNull(i);
            long totalRows = row.getLong(i++);
            collector.mergeQuery(queryType, truncatedText, fullTextSha1, totalDurationNanos,
                    executionCount, hasTotalRows, totalRows);
        }
        return insertQueries(collector.getSortedQueries(), rollup.rollupLevel(),
                rollup.agentRollupId(), query.transactionType(), query.transactionName(),
                query.to(), rollup.adjustedTTL(), rollupFromChildren);
    }

    private List<ResultSetFuture> rollupServiceCalls(RollupParams rollup, TransactionQuery query)
            throws IOException {
        ResultSet results = executeQueryForRollup(rollup.agentRollupId(), query, serviceCallTable);
        if (results.isExhausted()) {
            return ImmutableList.of();
        }
        return rollupServiceCallsFromRows(rollup, query, results);
    }

    private List<ResultSetFuture> rollupServiceCallsFromChildren(RollupParams rollup,
            TransactionQuery query, Collection<String> childAgentRollups) {
        List<Row> rows = getRowsForRollupFromChildren(query, childAgentRollups, serviceCallTable);
        if (rows.isEmpty()) {
            return ImmutableList.of();
        }
        return rollupServiceCallsFromRows(rollup, query, rows);
    }

    private List<ResultSetFuture> rollupServiceCallsFromRows(RollupParams rollup,
            TransactionQuery query, Iterable<Row> rows) {
        ServiceCallCollector collector =
                new ServiceCallCollector(rollup.maxAggregateServiceCallsPerType(), 0);
        for (Row row : rows) {
            int i = 0;
            String serviceCallType = checkNotNull(row.getString(i++));
            String serviceCallText = checkNotNull(row.getString(i++));
            double totalDurationNanos = row.getDouble(i++);
            long executionCount = row.getLong(i++);
            collector.mergeServiceCall(serviceCallType, serviceCallText, totalDurationNanos,
                    executionCount);
        }
        return insertServiceCalls(collector.toProto(), rollup.rollupLevel(), rollup.agentRollupId(),
                query.transactionType(), query.transactionName(), query.to(), rollup.adjustedTTL());
    }

    private List<ResultSetFuture> rollupThreadProfile(RollupParams rollup, TransactionQuery query,
            Table table) throws InvalidProtocolBufferException {
        ResultSet results = executeQueryForRollup(rollup.agentRollupId(), query, table);
        if (results.isExhausted()) {
            return ImmutableList.of();
        }
        return rollupThreadProfileFromRows(rollup, query, results, table);
    }

    private List<ResultSetFuture> rollupThreadProfileFromChildren(RollupParams rollup,
            TransactionQuery query, Collection<String> childAgentRollups, Table table)
            throws InvalidProtocolBufferException {
        List<Row> rows = getRowsForRollupFromChildren(query, childAgentRollups, table);
        if (rows.isEmpty()) {
            return ImmutableList.of();
        }
        return rollupThreadProfileFromRows(rollup, query, rows, table);
    }

    private List<ResultSetFuture> rollupThreadProfileFromRows(RollupParams rollup,
            TransactionQuery query, Iterable<Row> rows, Table table)
            throws InvalidProtocolBufferException {
        MutableProfile profile = new MutableProfile();
        for (Row row : rows) {
            ByteBuffer bytes = checkNotNull(row.getBytes(0));
            profile.merge(Profile.parseFrom(ByteString.copyFrom(bytes)));
        }
        BoundStatement boundStatement;
        if (query.transactionName() == null) {
            boundStatement = getInsertOverallPS(table, rollup.rollupLevel()).bind();
        } else {
            boundStatement = getInsertTransactionPS(table, rollup.rollupLevel()).bind();
        }
        int i = 0;
        boundStatement.setString(i++, rollup.agentRollupId());
        boundStatement.setString(i++, query.transactionType());
        if (query.transactionName() != null) {
            boundStatement.setString(i++, query.transactionName());
        }
        boundStatement.setTimestamp(i++, new Date(query.to()));
        boundStatement.setBytes(i++, toByteBuffer(profile.toProto()));
        boundStatement.setInt(i++, rollup.adjustedTTL());
        return ImmutableList.of(session.executeAsync(boundStatement));
    }

    private List<Row> getRowsForSummaryRollupFromChildren(TransactionQuery query,
            Collection<String> childAgentRollups, Table table) {
        List<Row> rows = Lists.newArrayList();
        for (String childAgentRollup : childAgentRollups) {
            BoundStatement boundStatement =
                    checkNotNull(readTransactionForRollupFromChildPS.get(table)).bind();
            bindQueryForRollupFromChild(boundStatement, childAgentRollup, query);
            for (Row row : session.execute(boundStatement)) {
                rows.add(row);
            }
        }
        return rows;
    }

    private List<Row> getRowsForRollupFromChildren(TransactionQuery query,
            Collection<String> childAgentRollups, Table table) {
        List<Row> rows = Lists.newArrayList();
        for (String childAgentRollup : childAgentRollups) {
            rows.addAll(executeQueryForRollupFromChild(childAgentRollup, query, table));
        }
        return rows;
    }

    private List<ResultSetFuture> storeOverallAggregate(String agentRollupId,
            String transactionType, long captureTime, Aggregate aggregate,
            List<Aggregate.SharedQueryText> sharedQueryTexts, int adjustedTTL) throws Exception {

        final int rollupLevel = 0;

        List<ResultSetFuture> futures = Lists.newArrayList();
        BoundStatement boundStatement = getInsertOverallPS(summaryTable, rollupLevel).bind();
        int i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, transactionType);
        boundStatement.setTimestamp(i++, new Date(captureTime));
        boundStatement.setDouble(i++, aggregate.getTotalDurationNanos());
        boundStatement.setLong(i++, aggregate.getTransactionCount());
        boundStatement.setInt(i++, adjustedTTL);
        futures.add(session.executeAsync(boundStatement));

        if (aggregate.getErrorCount() > 0) {
            boundStatement = getInsertOverallPS(errorSummaryTable, rollupLevel).bind();
            i = 0;
            boundStatement.setString(i++, agentRollupId);
            boundStatement.setString(i++, transactionType);
            boundStatement.setTimestamp(i++, new Date(captureTime));
            boundStatement.setLong(i++, aggregate.getErrorCount());
            boundStatement.setLong(i++, aggregate.getTransactionCount());
            boundStatement.setInt(i++, adjustedTTL);
            futures.add(session.executeAsync(boundStatement));
        }

        boundStatement = getInsertOverallPS(overviewTable, rollupLevel).bind();
        i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, transactionType);
        boundStatement.setTimestamp(i++, new Date(captureTime));
        bindAggregate(boundStatement, aggregate, i++, adjustedTTL);
        futures.add(session.executeAsync(boundStatement));

        boundStatement = getInsertOverallPS(histogramTable, rollupLevel).bind();
        i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, transactionType);
        boundStatement.setTimestamp(i++, new Date(captureTime));
        boundStatement.setDouble(i++, aggregate.getTotalDurationNanos());
        boundStatement.setLong(i++, aggregate.getTransactionCount());
        boundStatement.setBytes(i++, toByteBuffer(aggregate.getDurationNanosHistogram()));
        boundStatement.setInt(i++, adjustedTTL);
        futures.add(session.executeAsync(boundStatement));

        boundStatement = getInsertOverallPS(throughputTable, rollupLevel).bind();
        i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, transactionType);
        boundStatement.setTimestamp(i++, new Date(captureTime));
        boundStatement.setLong(i++, aggregate.getTransactionCount());
        boundStatement.setInt(i++, adjustedTTL);
        futures.add(session.executeAsync(boundStatement));

        if (aggregate.hasMainThreadProfile()) {
            Profile profile = aggregate.getMainThreadProfile();
            boundStatement = getInsertOverallPS(mainThreadProfileTable, rollupLevel).bind();
            i = 0;
            boundStatement.setString(i++, agentRollupId);
            boundStatement.setString(i++, transactionType);
            boundStatement.setTimestamp(i++, new Date(captureTime));
            boundStatement.setBytes(i++, toByteBuffer(profile));
            boundStatement.setInt(i++, adjustedTTL);
            futures.add(session.executeAsync(boundStatement));
        }
        if (aggregate.hasAuxThreadProfile()) {
            Profile profile = aggregate.getAuxThreadProfile();
            boundStatement = getInsertOverallPS(auxThreadProfileTable, rollupLevel).bind();
            i = 0;
            boundStatement.setString(i++, agentRollupId);
            boundStatement.setString(i++, transactionType);
            boundStatement.setTimestamp(i++, new Date(captureTime));
            boundStatement.setBytes(i++, toByteBuffer(profile));
            boundStatement.setInt(i++, adjustedTTL);
            futures.add(session.executeAsync(boundStatement));
        }
        futures.addAll(insertQueries(aggregate.getQueriesByTypeList(), sharedQueryTexts,
                rollupLevel, agentRollupId, transactionType, null, captureTime, adjustedTTL));
        futures.addAll(insertServiceCalls(aggregate.getServiceCallsByTypeList(), rollupLevel,
                agentRollupId, transactionType, null, captureTime, adjustedTTL));
        return futures;
    }

    private List<ResultSetFuture> storeTransactionAggregate(String agentRollupId,
            String transactionType, String transactionName, long captureTime, Aggregate aggregate,
            List<Aggregate.SharedQueryText> sharedQueryTexts, int adjustedTTL) throws IOException {

        final int rollupLevel = 0;

        List<ResultSetFuture> futures = Lists.newArrayList();
        BoundStatement boundStatement = getInsertTransactionPS(summaryTable, rollupLevel).bind();
        int i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, transactionType);
        boundStatement.setTimestamp(i++, new Date(captureTime));
        boundStatement.setString(i++, transactionName);
        boundStatement.setDouble(i++, aggregate.getTotalDurationNanos());
        boundStatement.setLong(i++, aggregate.getTransactionCount());
        boundStatement.setInt(i++, adjustedTTL);
        futures.add(session.executeAsync(boundStatement));

        if (aggregate.getErrorCount() > 0) {
            boundStatement = getInsertTransactionPS(errorSummaryTable, rollupLevel).bind();
            i = 0;
            boundStatement.setString(i++, agentRollupId);
            boundStatement.setString(i++, transactionType);
            boundStatement.setTimestamp(i++, new Date(captureTime));
            boundStatement.setString(i++, transactionName);
            boundStatement.setLong(i++, aggregate.getErrorCount());
            boundStatement.setLong(i++, aggregate.getTransactionCount());
            boundStatement.setInt(i++, adjustedTTL);
            futures.add(session.executeAsync(boundStatement));
        }

        boundStatement = getInsertTransactionPS(overviewTable, rollupLevel).bind();
        i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, transactionType);
        boundStatement.setString(i++, transactionName);
        boundStatement.setTimestamp(i++, new Date(captureTime));
        bindAggregate(boundStatement, aggregate, i++, adjustedTTL);
        futures.add(session.executeAsync(boundStatement));

        boundStatement = getInsertTransactionPS(histogramTable, rollupLevel).bind();
        i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, transactionType);
        boundStatement.setString(i++, transactionName);
        boundStatement.setTimestamp(i++, new Date(captureTime));
        boundStatement.setDouble(i++, aggregate.getTotalDurationNanos());
        boundStatement.setLong(i++, aggregate.getTransactionCount());
        boundStatement.setBytes(i++, toByteBuffer(aggregate.getDurationNanosHistogram()));
        boundStatement.setInt(i++, adjustedTTL);
        futures.add(session.executeAsync(boundStatement));

        boundStatement = getInsertTransactionPS(throughputTable, rollupLevel).bind();
        i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, transactionType);
        boundStatement.setString(i++, transactionName);
        boundStatement.setTimestamp(i++, new Date(captureTime));
        boundStatement.setLong(i++, aggregate.getTransactionCount());
        boundStatement.setInt(i++, adjustedTTL);
        futures.add(session.executeAsync(boundStatement));

        if (aggregate.hasMainThreadProfile()) {
            Profile profile = aggregate.getMainThreadProfile();
            boundStatement = getInsertTransactionPS(mainThreadProfileTable, rollupLevel).bind();
            i = 0;
            boundStatement.setString(i++, agentRollupId);
            boundStatement.setString(i++, transactionType);
            boundStatement.setString(i++, transactionName);
            boundStatement.setTimestamp(i++, new Date(captureTime));
            boundStatement.setBytes(i++, toByteBuffer(profile));
            boundStatement.setInt(i++, adjustedTTL);
            futures.add(session.executeAsync(boundStatement));
        }
        if (aggregate.hasAuxThreadProfile()) {
            Profile profile = aggregate.getAuxThreadProfile();
            boundStatement = getInsertTransactionPS(auxThreadProfileTable, rollupLevel).bind();
            i = 0;
            boundStatement.setString(i++, agentRollupId);
            boundStatement.setString(i++, transactionType);
            boundStatement.setString(i++, transactionName);
            boundStatement.setTimestamp(i++, new Date(captureTime));
            boundStatement.setBytes(i++, toByteBuffer(profile));
            boundStatement.setInt(i++, adjustedTTL);
            futures.add(session.executeAsync(boundStatement));
        }
        futures.addAll(
                insertQueries(aggregate.getQueriesByTypeList(), sharedQueryTexts, rollupLevel,
                        agentRollupId, transactionType, transactionName, captureTime, adjustedTTL));
        futures.addAll(insertServiceCalls(aggregate.getServiceCallsByTypeList(), rollupLevel,
                agentRollupId, transactionType, transactionName, captureTime, adjustedTTL));
        return futures;
    }

    private List<ResultSetFuture> insertQueries(List<Aggregate.QueriesByType> queriesByTypeList,
            List<Aggregate.SharedQueryText> sharedQueryTexts, int rollupLevel, String agentRollupId,
            String transactionType, @Nullable String transactionName, long captureTime,
            int adjustedTTL) {
        List<ResultSetFuture> futures = Lists.newArrayList();
        for (Aggregate.QueriesByType queriesByType : queriesByTypeList) {
            for (Aggregate.Query query : queriesByType.getQueryList()) {
                Aggregate.SharedQueryText sharedQueryText =
                        sharedQueryTexts.get(query.getSharedQueryTextIndex());
                BoundStatement boundStatement;
                if (transactionName == null) {
                    boundStatement = getInsertOverallPS(queryTable, rollupLevel).bind();
                } else {
                    boundStatement = getInsertTransactionPS(queryTable, rollupLevel).bind();
                }
                int i = 0;
                boundStatement.setString(i++, agentRollupId);
                boundStatement.setString(i++, transactionType);
                if (transactionName != null) {
                    boundStatement.setString(i++, transactionName);
                }
                boundStatement.setTimestamp(i++, new Date(captureTime));
                boundStatement.setString(i++, queriesByType.getType());
                String fullTextSha1 = sharedQueryText.getFullTextSha1();
                if (fullTextSha1.isEmpty()) {
                    boundStatement.setString(i++, sharedQueryText.getFullText());
                    // full_query_text_sha1 cannot be null since it is used in clustering key
                    boundStatement.setString(i++, "");
                } else {
                    boundStatement.setString(i++, sharedQueryText.getTruncatedText());
                    boundStatement.setString(i++, fullTextSha1);
                }
                boundStatement.setDouble(i++, query.getTotalDurationNanos());
                boundStatement.setLong(i++, query.getExecutionCount());
                if (query.hasTotalRows()) {
                    boundStatement.setLong(i++, query.getTotalRows().getValue());
                } else {
                    boundStatement.setToNull(i++);
                }
                boundStatement.setInt(i++, adjustedTTL);
                futures.add(session.executeAsync(boundStatement));
            }
        }
        return futures;
    }

    private List<ResultSetFuture> insertQueries(Map<String, List<MutableQuery>> map,
            int rollupLevel, String agentRollupId, String transactionType,
            @Nullable String transactionName, long captureTime, int adjustedTTL,
            boolean rollupFromChildren) throws Exception {
        List<ResultSetFuture> futures = Lists.newArrayList();
        for (Entry<String, List<MutableQuery>> entry : map.entrySet()) {
            for (MutableQuery query : entry.getValue()) {
                BoundStatement boundStatement;
                if (transactionName == null) {
                    boundStatement = getInsertOverallPS(queryTable, rollupLevel).bind();
                } else {
                    boundStatement = getInsertTransactionPS(queryTable, rollupLevel).bind();
                }
                String fullTextSha1 = query.getFullTextSha1();
                int i = 0;
                boundStatement.setString(i++, agentRollupId);
                boundStatement.setString(i++, transactionType);
                if (transactionName != null) {
                    boundStatement.setString(i++, transactionName);
                }
                boundStatement.setTimestamp(i++, new Date(captureTime));
                boundStatement.setString(i++, entry.getKey());
                boundStatement.setString(i++, query.getTruncatedText());
                // full_query_text_sha1 cannot be null since it is used in clustering key
                boundStatement.setString(i++, Strings.nullToEmpty(fullTextSha1));
                boundStatement.setDouble(i++, query.getTotalDurationNanos());
                boundStatement.setLong(i++, query.getExecutionCount());
                if (query.hasTotalRows()) {
                    boundStatement.setLong(i++, query.getTotalRows());
                } else {
                    boundStatement.setToNull(i++);
                }
                boundStatement.setInt(i++, adjustedTTL);
                futures.add(session.executeAsync(boundStatement));
                if (rollupFromChildren && fullTextSha1 != null) {
                    futures.addAll(fullQueryTextDao.updateTTL(agentRollupId, fullTextSha1));
                }
            }
        }
        return futures;
    }

    private List<ResultSetFuture> insertServiceCalls(
            List<Aggregate.ServiceCallsByType> serviceCallsByTypeList, int rollupLevel,
            String agentRollupId, String transactionType, @Nullable String transactionName,
            long captureTime, int adjustedTTL) {
        List<ResultSetFuture> futures = Lists.newArrayList();
        for (Aggregate.ServiceCallsByType serviceCallsByType : serviceCallsByTypeList) {
            for (Aggregate.ServiceCall serviceCall : serviceCallsByType.getServiceCallList()) {
                BoundStatement boundStatement;
                if (transactionName == null) {
                    boundStatement = getInsertOverallPS(serviceCallTable, rollupLevel).bind();
                } else {
                    boundStatement = getInsertTransactionPS(serviceCallTable, rollupLevel).bind();
                }
                int i = 0;
                boundStatement.setString(i++, agentRollupId);
                boundStatement.setString(i++, transactionType);
                if (transactionName != null) {
                    boundStatement.setString(i++, transactionName);
                }
                boundStatement.setTimestamp(i++, new Date(captureTime));
                boundStatement.setString(i++, serviceCallsByType.getType());
                boundStatement.setString(i++, serviceCall.getText());
                boundStatement.setDouble(i++, serviceCall.getTotalDurationNanos());
                boundStatement.setLong(i++, serviceCall.getExecutionCount());
                boundStatement.setInt(i++, adjustedTTL);
                futures.add(session.executeAsync(boundStatement));
            }
        }
        return futures;
    }

    private PreparedStatement getInsertOverallPS(Table table, int rollupLevel) {
        return checkNotNull(insertOverallPS.get(table)).get(rollupLevel);
    }

    private PreparedStatement getInsertTransactionPS(Table table, int rollupLevel) {
        return checkNotNull(insertTransactionPS.get(table)).get(rollupLevel);
    }

    private void bindAggregate(BoundStatement boundStatement, Aggregate aggregate, int startIndex,
            int adjustedTTL) throws IOException {
        int i = startIndex;
        boundStatement.setDouble(i++, aggregate.getTotalDurationNanos());
        boundStatement.setLong(i++, aggregate.getTransactionCount());
        boundStatement.setBool(i++, aggregate.getAsyncTransactions());
        List<Aggregate.Timer> mainThreadRootTimers = aggregate.getMainThreadRootTimerList();
        if (!mainThreadRootTimers.isEmpty()) {
            boundStatement.setBytes(i++, Messages.toByteBuffer(mainThreadRootTimers));
        } else {
            boundStatement.setToNull(i++);
        }
        List<Aggregate.Timer> auxThreadRootTimers = aggregate.getAuxThreadRootTimerList();
        if (!auxThreadRootTimers.isEmpty()) {
            boundStatement.setBytes(i++, Messages.toByteBuffer(auxThreadRootTimers));
        } else {
            boundStatement.setToNull(i++);
        }
        List<Aggregate.Timer> asyncTimers = aggregate.getAsyncTimerList();
        if (!asyncTimers.isEmpty()) {
            boundStatement.setBytes(i++, Messages.toByteBuffer(asyncTimers));
        } else {
            boundStatement.setToNull(i++);
        }
        Aggregate.ThreadStats mainThreadStats = aggregate.getMainThreadStats();
        if (mainThreadStats.hasTotalCpuNanos()) {
            boundStatement.setDouble(i++, mainThreadStats.getTotalCpuNanos().getValue());
        } else {
            boundStatement.setToNull(i++);
        }
        if (mainThreadStats.hasTotalBlockedNanos()) {
            boundStatement.setDouble(i++, mainThreadStats.getTotalBlockedNanos().getValue());
        } else {
            boundStatement.setToNull(i++);
        }
        if (mainThreadStats.hasTotalWaitedNanos()) {
            boundStatement.setDouble(i++, mainThreadStats.getTotalWaitedNanos().getValue());
        } else {
            boundStatement.setToNull(i++);
        }
        if (mainThreadStats.hasTotalAllocatedBytes()) {
            boundStatement.setDouble(i++, mainThreadStats.getTotalAllocatedBytes().getValue());
        } else {
            boundStatement.setToNull(i++);
        }
        Aggregate.ThreadStats auxThreadStats = aggregate.getMainThreadStats();
        if (auxThreadStats.hasTotalCpuNanos()) {
            boundStatement.setDouble(i++, auxThreadStats.getTotalCpuNanos().getValue());
        } else {
            boundStatement.setToNull(i++);
        }
        if (auxThreadStats.hasTotalBlockedNanos()) {
            boundStatement.setDouble(i++, auxThreadStats.getTotalBlockedNanos().getValue());
        } else {
            boundStatement.setToNull(i++);
        }
        if (auxThreadStats.hasTotalWaitedNanos()) {
            boundStatement.setDouble(i++, auxThreadStats.getTotalWaitedNanos().getValue());
        } else {
            boundStatement.setToNull(i++);
        }
        if (auxThreadStats.hasTotalAllocatedBytes()) {
            boundStatement.setDouble(i++, auxThreadStats.getTotalAllocatedBytes().getValue());
        } else {
            boundStatement.setToNull(i++);
        }
        boundStatement.setInt(i++, adjustedTTL);
    }

    private ResultSet createBoundStatement(String agentRollupId, OverallQuery query, Table table) {
        BoundStatement boundStatement =
                checkNotNull(readOverallPS.get(table)).get(query.rollupLevel()).bind();
        bindQuery(boundStatement, agentRollupId, query);
        return session.execute(boundStatement);
    }

    private ResultSet executeQuery(String agentRollupId, TransactionQuery query, Table table) {
        BoundStatement boundStatement;
        if (query.transactionName() == null) {
            boundStatement = checkNotNull(readOverallPS.get(table)).get(query.rollupLevel()).bind();
        } else {
            boundStatement =
                    checkNotNull(readTransactionPS.get(table)).get(query.rollupLevel()).bind();
        }
        bindQuery(boundStatement, agentRollupId, query);
        return session.execute(boundStatement);
    }

    private ResultSet executeQueryForRollup(String agentRollupId, TransactionQuery query,
            Table table) {
        BoundStatement boundStatement;
        if (query.transactionName() == null) {
            boundStatement =
                    checkNotNull(readOverallForRollupPS.get(table)).get(query.rollupLevel()).bind();
        } else {
            boundStatement = checkNotNull(readTransactionForRollupPS.get(table))
                    .get(query.rollupLevel()).bind();
        }
        bindQuery(boundStatement, agentRollupId, query);
        return session.execute(boundStatement);
    }

    private List<Row> executeQueryForRollupFromChild(String childAgentRollup,
            TransactionQuery query, Table table) {
        BoundStatement boundStatement;
        if (query.transactionName() == null) {
            boundStatement = checkNotNull(readOverallForRollupFromChildPS.get(table)).bind();
        } else {
            boundStatement = checkNotNull(readTransactionForRollupFromChildPS.get(table)).bind();
        }
        bindQueryForRollupFromChild(boundStatement, childAgentRollup, query);
        return session.execute(boundStatement).all();
    }

    private void mergeProfilesInto(String agentRollupId, TransactionQuery query, Table profileTable,
            ProfileCollector collector) throws InvalidProtocolBufferException {
        ResultSet results = executeQuery(agentRollupId, query, profileTable);
        long captureTime = Long.MIN_VALUE;
        for (Row row : results) {
            captureTime = Math.max(captureTime, checkNotNull(row.getTimestamp(0)).getTime());
            ByteBuffer bytes = checkNotNull(row.getBytes(1));
            // TODO optimize this byte copying
            Profile profile = Profile.parseFrom(ByteString.copyFrom(bytes));
            collector.mergeProfile(profile);
            collector.updateLastCaptureTime(captureTime);
        }
    }

    private List<Integer> getTTLs() throws Exception {
        List<Integer> ttls = Lists.newArrayList();
        List<Integer> rollupExpirationHours =
                configRepository.getStorageConfig().rollupExpirationHours();
        for (long expirationHours : rollupExpirationHours) {
            ttls.add(Ints.saturatedCast(HOURS.toSeconds(expirationHours)));
        }
        return ttls;
    }

    private RollupParams getRollupParams(String agentRollupId, int rollupLevel, int adjustedTTL)
            throws Exception {
        ImmutableRollupParams.Builder rollupInfo = ImmutableRollupParams.builder()
                .agentRollupId(agentRollupId)
                .rollupLevel(rollupLevel)
                .adjustedTTL(adjustedTTL);
        AdvancedConfig advancedConfig = configRepository.getAdvancedConfig(agentRollupId);
        if (advancedConfig != null && advancedConfig.hasMaxAggregateQueriesPerType()) {
            rollupInfo.maxAggregateQueriesPerType(
                    advancedConfig.getMaxAggregateQueriesPerType().getValue());
        } else {
            rollupInfo.maxAggregateQueriesPerType(ConfigDefaults.MAX_AGGREGATE_QUERIES_PER_TYPE);
        }
        if (advancedConfig != null && advancedConfig.hasMaxAggregateServiceCallsPerType()) {
            rollupInfo.maxAggregateServiceCallsPerType(
                    advancedConfig.getMaxAggregateServiceCallsPerType().getValue());
        } else {
            rollupInfo.maxAggregateServiceCallsPerType(
                    ConfigDefaults.MAX_AGGREGATE_SERVICE_CALLS_PER_TYPE);
        }
        return rollupInfo.build();
    }

    static int getAdjustedTTL(int ttl, long captureTime, Clock clock) {
        if (ttl == 0) {
            return 0;
        }
        int captureTimeAgoSeconds =
                Ints.saturatedCast(MILLISECONDS.toSeconds(clock.currentTimeMillis() - captureTime));
        int adjustedTTL = ttl - captureTimeAgoSeconds;
        // max is a safety guard
        return Math.max(adjustedTTL, 60);
    }

    static int getNeedsRollupAdjustedTTL(int adjustedTTL, List<RollupConfig> rollupConfigs) {
        if (adjustedTTL == 0) {
            return 0;
        }
        long maxRollupInterval = rollupConfigs.get(rollupConfigs.size() - 1).intervalMillis();
        // reduced by an extra 1 hour to make sure that once needs rollup record is retrieved,
        // there is plenty of time to read the all of the data records in the interval before they
        // expire (reading partially expired interval can lead to non-idempotent rollups)
        int needsRollupAdjustedTTL =
                adjustedTTL - Ints.saturatedCast(MILLISECONDS.toSeconds(maxRollupInterval)) - 3600;
        // max is a safety guard
        return Math.max(needsRollupAdjustedTTL, 60);
    }

    static List<NeedsRollup> getNeedsRollupList(String agentRollupId, int rollupLevel,
            long rollupIntervalMillis, List<PreparedStatement> readNeedsRollup, Session session,
            Clock clock) {
        BoundStatement boundStatement = readNeedsRollup.get(rollupLevel - 1).bind();
        boundStatement.setString(0, agentRollupId);
        ResultSet results = session.execute(boundStatement);
        Map<Long, NeedsRollup> needsRollupMap = Maps.newLinkedHashMap();
        for (Row row : results) {
            int i = 0;
            long captureTime = checkNotNull(row.getTimestamp(i++)).getTime();
            UUID uniqueness = row.getUUID(i++);
            Set<String> keys = checkNotNull(row.getSet(i++, String.class));
            NeedsRollup needsRollup = needsRollupMap.get(captureTime);
            if (needsRollup == null) {
                needsRollup = new NeedsRollup(captureTime);
                needsRollupMap.put(captureTime, needsRollup);
            }
            needsRollup.keys.addAll(keys);
            needsRollup.uniquenessKeysForDeletion.add(uniqueness);
        }
        if (needsRollupMap.isEmpty()) {
            return ImmutableList.of();
        }
        List<NeedsRollup> needsRollupList = Lists.newArrayList(needsRollupMap.values());
        NeedsRollup lastNeedsRollup = needsRollupList.get(needsRollupList.size() - 1);
        if (lastNeedsRollup.getCaptureTime() > clock.currentTimeMillis() - rollupIntervalMillis) {
            // normally, the last "needs rollup" capture time is in the near future, so don't roll
            // it up since it is likely still being added to
            //
            // this is mostly to avoid rolling up this data twice, but also currently the UI assumes
            // when it finds rolled up data, it doesn't check for non-rolled up data for same
            // interval
            //
            // the above conditional is to force the rollup of the last "needs rollup" if it is more
            // than one rollup interval in the past, otherwise the last "needs rollup" could expire
            // due to TTL prior to it being rolled up
            needsRollupList.remove(needsRollupList.size() - 1);
        }
        return needsRollupList;
    }

    static List<NeedsRollupFromChildren> getNeedsRollupFromChildrenList(String agentRollupId,
            PreparedStatement readNeedsRollupFromChild, Session session) {
        BoundStatement boundStatement = readNeedsRollupFromChild.bind();
        boundStatement.setString(0, agentRollupId);
        ResultSet results = session.execute(boundStatement);
        Map<Long, NeedsRollupFromChildren> needsRollupFromChildrenMap = Maps.newLinkedHashMap();
        for (Row row : results) {
            int i = 0;
            long captureTime = checkNotNull(row.getTimestamp(i++)).getTime();
            UUID uniqueness = row.getUUID(i++);
            String childAgentRollup = checkNotNull(row.getString(i++));
            Set<String> keys = checkNotNull(row.getSet(i++, String.class));
            NeedsRollupFromChildren needsRollup = needsRollupFromChildrenMap.get(captureTime);
            if (needsRollup == null) {
                needsRollup = new NeedsRollupFromChildren(captureTime);
                needsRollupFromChildrenMap.put(captureTime, needsRollup);
            }
            for (String key : keys) {
                needsRollup.keys.put(key, childAgentRollup);
            }
            needsRollup.uniquenessKeysForDeletion.add(uniqueness);
        }
        return ImmutableList.copyOf(needsRollupFromChildrenMap.values());
    }

    // it is important that the insert into next needs_rollup happens after present
    // rollup and before deleting present rollup
    // if insert before present rollup then possible for the next rollup to occur before
    // present rollup has completed
    // if insert after deleting present rollup then possible for error to occur in between
    // and insert would never happen
    static void postRollup(String agentRollupId, long captureTime, Set<String> keys,
            Set<UUID> uniquenessKeysForDeletion, @Nullable Long nextRollupIntervalMillis,
            @Nullable PreparedStatement insertNeedsRollup, PreparedStatement deleteNeedsRollup,
            int needsRollupAdjustedTTL, Session session) throws Exception {
        if (nextRollupIntervalMillis != null) {
            checkNotNull(insertNeedsRollup);
            long rollupCaptureTime = Utils.getRollupCaptureTime(captureTime,
                    nextRollupIntervalMillis);
            BoundStatement boundStatement = insertNeedsRollup.bind();
            int i = 0;
            boundStatement.setString(i++, agentRollupId);
            boundStatement.setTimestamp(i++, new Date(rollupCaptureTime));
            boundStatement.setUUID(i++, UUIDs.timeBased());
            boundStatement.setSet(i++, keys);
            boundStatement.setInt(i++, needsRollupAdjustedTTL);
            // intentionally not async, see method-level comment
            session.execute(boundStatement);
        }
        List<ResultSetFuture> futures = Lists.newArrayList();
        for (UUID uniqueness : uniquenessKeysForDeletion) {
            BoundStatement boundStatement = deleteNeedsRollup.bind();
            int i = 0;
            boundStatement.setString(i++, agentRollupId);
            boundStatement.setTimestamp(i++, new Date(captureTime));
            boundStatement.setUUID(i++, uniqueness);
            futures.add(session.executeAsync(boundStatement));
        }
        MoreFutures.waitForAll(futures);
    }

    private static void bindQuery(BoundStatement boundStatement, String agentRollupId,
            OverallQuery query) {
        int i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, query.transactionType());
        boundStatement.setTimestamp(i++, new Date(query.from()));
        boundStatement.setTimestamp(i++, new Date(query.to()));
    }

    private static void bindQuery(BoundStatement boundStatement, String agentRollupId,
            TransactionQuery query) {
        int i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, query.transactionType());
        String transactionName = query.transactionName();
        if (transactionName != null) {
            boundStatement.setString(i++, transactionName);
        }
        boundStatement.setTimestamp(i++, new Date(query.from()));
        boundStatement.setTimestamp(i++, new Date(query.to()));
    }

    private static void bindQueryForRollupFromChild(BoundStatement boundStatement,
            String agentRollupId, TransactionQuery query) {
        int i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, query.transactionType());
        String transactionName = query.transactionName();
        if (transactionName != null) {
            boundStatement.setString(i++, transactionName);
        }
        boundStatement.setTimestamp(i++, new Date(query.to()));
    }

    private static String createTableQuery(Table table, boolean transaction, int i) {
        StringBuilder sb = new StringBuilder();
        sb.append("create table if not exists ");
        sb.append(getTableName(table.partialName(), transaction, i));
        sb.append(" (agent_rollup varchar, transaction_type varchar");
        if (transaction) {
            sb.append(", transaction_name varchar");
        }
        sb.append(", capture_time timestamp");
        for (Column column : table.columns()) {
            sb.append(", ");
            sb.append(column.name());
            sb.append(" ");
            sb.append(column.type());
        }
        sb.append(", primary key ((agent_rollup, transaction_type");
        if (transaction) {
            sb.append(", transaction_name");
        }
        sb.append("), capture_time");
        for (String clusterKey : table.clusterKey()) {
            sb.append(", ");
            sb.append(clusterKey);
        }
        sb.append("))");
        return sb.toString();
    }

    private static String insertPS(Table table, boolean transaction, int i) {
        StringBuilder sb = new StringBuilder();
        sb.append("insert into ");
        sb.append(getTableName(table.partialName(), transaction, i));
        sb.append(" (agent_rollup, transaction_type");
        if (transaction) {
            sb.append(", transaction_name");
        }
        sb.append(", capture_time");
        for (Column column : table.columns()) {
            sb.append(", ");
            sb.append(column.name());
        }
        sb.append(") values (?, ?, ?");
        if (transaction) {
            sb.append(", ?");
        }
        sb.append(Strings.repeat(", ?", table.columns().size()));
        sb.append(") using TTL ?");
        return sb.toString();
    }

    private static String readPS(Table table, boolean transaction, int i) {
        StringBuilder sb = new StringBuilder();
        sb.append("select capture_time");
        for (Column column : table.columns()) {
            sb.append(", ");
            sb.append(column.name());
        }
        sb.append(" from ");
        sb.append(getTableName(table.partialName(), transaction, i));
        sb.append(" where agent_rollup = ? and transaction_type = ?");
        if (transaction) {
            sb.append(" and transaction_name = ?");
        }
        sb.append(" and capture_time >");
        if (table.fromInclusive()) {
            sb.append("=");
        }
        sb.append(" ? and capture_time <= ?");
        return sb.toString();
    }

    private static String readForRollupPS(Table table, boolean transaction, int i) {
        StringBuilder sb = new StringBuilder();
        sb.append("select ");
        appendColumnNames(sb, table.columns());
        sb.append(" from ");
        sb.append(getTableName(table.partialName(), transaction, i));
        sb.append(" where agent_rollup = ? and transaction_type = ?");
        if (transaction) {
            sb.append(" and transaction_name = ?");
        }
        sb.append(" and capture_time > ? and capture_time <= ?");
        return sb.toString();
    }

    private static String readForRollupFromChildPS(Table table, boolean transaction, int i) {
        StringBuilder sb = new StringBuilder();
        sb.append("select ");
        appendColumnNames(sb, table.columns());
        sb.append(" from ");
        sb.append(getTableName(table.partialName(), transaction, i));
        sb.append(" where agent_rollup = ? and transaction_type = ?");
        if (transaction) {
            sb.append(" and transaction_name = ?");
        }
        sb.append(" and capture_time = ?");
        return sb.toString();
    }

    private static String existsPS(Table table, boolean transaction, int i) {
        StringBuilder sb = new StringBuilder();
        sb.append("select agent_rollup");
        sb.append(" from ");
        sb.append(getTableName(table.partialName(), transaction, i));
        sb.append(" where agent_rollup = ? and transaction_type = ?");
        if (transaction) {
            sb.append(" and transaction_name = ?");
        }
        sb.append(" and capture_time >");
        if (table.fromInclusive()) {
            sb.append("=");
        }
        sb.append(" ? and capture_time <= ? limit 1");
        return sb.toString();
    }

    private static String createSummaryTableQuery(Table table, boolean transaction, int i) {
        StringBuilder sb = new StringBuilder();
        sb.append("create table if not exists ");
        sb.append(getTableName(table.partialName(), transaction, i));
        sb.append(" (agent_rollup varchar, transaction_type varchar, capture_time timestamp");
        if (transaction) {
            sb.append(", transaction_name varchar");
        }
        for (Column column : table.columns()) {
            sb.append(", ");
            sb.append(column.name());
            sb.append(" ");
            sb.append(column.type());
        }
        sb.append(", primary key ((agent_rollup, transaction_type), capture_time");
        if (transaction) {
            sb.append(", transaction_name");
        }
        sb.append("))");
        return sb.toString();
    }

    private static String insertSummaryPS(Table table, boolean transaction, int i) {
        StringBuilder sb = new StringBuilder();
        sb.append("insert into ");
        sb.append(getTableName(table.partialName(), transaction, i));
        sb.append(" (agent_rollup, transaction_type, capture_time");
        if (transaction) {
            sb.append(", transaction_name");
        }
        for (Column column : table.columns()) {
            sb.append(", ");
            sb.append(column.name());
        }
        sb.append(") values (?, ?, ?");
        if (transaction) {
            sb.append(", ?");
        }
        sb.append(Strings.repeat(", ?", table.columns().size()));
        sb.append(") using TTL ?");
        return sb.toString();
    }

    // currently have to do group by / sort / limit client-side, even on overall_summary
    // because sum(double) requires Cassandra 2.2+
    private static String readSummaryPS(Table table, boolean transaction, int i) {
        StringBuilder sb = new StringBuilder();
        // capture_time is needed to keep track of lastCaptureTime for rollup level when merging
        // recent non-rolled up data
        sb.append("select capture_time");
        if (transaction) {
            sb.append(", transaction_name");
        }
        for (Column column : table.columns()) {
            sb.append(", ");
            sb.append(column.name());
        }
        sb.append(" from ");
        sb.append(getTableName(table.partialName(), transaction, i));
        sb.append(" where agent_rollup = ? and transaction_type = ? and capture_time >");
        if (table.fromInclusive()) {
            sb.append("=");
        }
        sb.append(" ? and capture_time <= ?");
        return sb.toString();
    }

    private static String readSummaryForRollupPS(Table table, boolean transaction, int i) {
        StringBuilder sb = new StringBuilder();
        sb.append("select ");
        if (transaction) {
            sb.append("transaction_name, ");
        }
        appendColumnNames(sb, table.columns());
        sb.append(" from ");
        sb.append(getTableName(table.partialName(), transaction, i));
        sb.append(" where agent_rollup = ? and transaction_type = ? and capture_time > ?");
        sb.append(" and capture_time <= ?");
        return sb.toString();
    }

    private static String readSummaryForRollupFromChildPS(Table table, boolean transaction,
            int i) {
        StringBuilder sb = new StringBuilder();
        sb.append("select ");
        if (transaction) {
            sb.append("transaction_name, ");
        }
        appendColumnNames(sb, table.columns());
        sb.append(" from ");
        sb.append(getTableName(table.partialName(), transaction, i));
        sb.append(" where agent_rollup = ? and transaction_type = ? and capture_time = ?");
        return sb.toString();
    }

    private static String getTableName(String partialName, boolean transaction, int i) {
        StringBuilder sb = new StringBuilder();
        sb.append("aggregate_");
        if (transaction) {
            sb.append("tn_");
        } else {
            sb.append("tt_");
        }
        sb.append(partialName);
        sb.append("_rollup_");
        sb.append(i);
        return sb.toString();
    }

    private static void appendColumnNames(StringBuilder sb, List<Column> columns) {
        boolean addSeparator = false;
        for (Column column : columns) {
            if (addSeparator) {
                sb.append(", ");
            }
            sb.append(column.name());
            addSeparator = true;
        }
    }

    private static ByteBuffer toByteBuffer(AbstractMessage message) {
        return ByteBuffer.wrap(message.toByteString().toByteArray());
    }

    @Value.Immutable
    interface Table {
        String partialName();
        List<Column> columns();
        List<String> clusterKey();
        boolean summary();
        boolean fromInclusive();
    }

    @Value.Immutable
    @Styles.AllParameters
    interface Column {
        String name();
        String type();
    }

    @Value.Immutable
    interface RollupParams {
        String agentRollupId();
        int rollupLevel();
        int adjustedTTL();
        int maxAggregateQueriesPerType();
        int maxAggregateServiceCallsPerType();
    }

    static class NeedsRollup {

        private final long captureTime;
        private final Set<String> keys = Sets.newHashSet(); // transaction types or gauge names
        private final Set<UUID> uniquenessKeysForDeletion = Sets.newHashSet();

        private NeedsRollup(long captureTime) {
            this.captureTime = captureTime;
        }

        long getCaptureTime() {
            return captureTime;
        }

        Set<String> getKeys() {
            return keys;
        }

        Set<UUID> getUniquenessKeysForDeletion() {
            return uniquenessKeysForDeletion;
        }
    }

    static class NeedsRollupFromChildren {

        private final long captureTime;
        // map keys are transaction types or gauge names
        // map values are childAgentRollups
        private final Multimap<String, String> keys = HashMultimap.create();
        private final Set<UUID> uniquenessKeysForDeletion = Sets.newHashSet();

        private NeedsRollupFromChildren(long captureTime) {
            this.captureTime = captureTime;
        }

        long getCaptureTime() {
            return captureTime;
        }

        Multimap<String, String> getKeys() {
            return keys;
        }

        Set<UUID> getUniquenessKeysForDeletion() {
            return uniquenessKeysForDeletion;
        }
    }

    private static class MutableSummary {
        private double totalDurationNanos;
        private long transactionCount;
    }

    private static class MutableErrorSummary {
        private long errorCount;
        private long transactionCount;
    }
}
