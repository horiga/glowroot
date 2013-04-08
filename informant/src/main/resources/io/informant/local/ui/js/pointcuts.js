/*
 * Copyright 2012-2013 the original author or authors.
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
(function () {
  'use strict';

  var pointcutTemplate = Handlebars.compile($('#pointcutTemplate').html());

  function read() {
    Informant.showSpinner('#initialLoadSpinner');
    $.getJSON('config/read', function (config) {
      Informant.hideSpinner('#initialLoadSpinner');
      var pointcutConfigs = config.pointcutConfigs;
      var i;
      for (i = 0; i < pointcutConfigs.length; i++) {
        applyPointcutEditTemplate(pointcutConfigs[i]);
      }
    });
  }

  var pointcutCounter = 0;

  function applyPointcutEditTemplate(pointcut) {
    var pointcutNum = pointcutCounter++;

    function matchingTypeNames(partialTypeName, callback) {
      var url = 'pointcut/matching-type-names?partial-type-name=' + partialTypeName + '&limit=7';
      $.getJSON(url, function (matchingTypeNames) {
        callback(matchingTypeNames);
      });
    }

    function matchingMethodNames(partialMethodName, callback) {
      var url = 'pointcut/matching-method-names?type-name='
          + $('#pointcutTypeName_' + pointcutNum).val() + '&partial-method-name='
          + partialMethodName + '&limit=7';
      $.getJSON(url, function (matchingMethodNames) {
        callback(matchingMethodNames);
      });
    }

    function signatureText(modifiers, returnType, methodName, argTypes) {
      var signature = '';
      var i;
      for (i = 0; i < modifiers.length; i++) {
        signature += modifiers[i].toLowerCase() + ' ';
      }
      signature += returnType + ' ' + methodName + '(';
      for (i = 0; i < argTypes.length; i++) {
        if (i > 0) {
          signature += ', ';
        }
        signature += argTypes[i];
      }
      signature += ')';
      return signature;
    }

    function updateSpanTemplate() {
      var signature = getSignature();
      if (!signature) {
        // no radio button selected
        return;
      }
      var template = $('#pointcutTypeName_' + pointcutNum).val() + '.' + signature.name + '()';
      var i;
      for (i = 0; i < signature.argTypeNames.length; i++) {
        if (i === 0) {
          template += ': {{' + i + '}}';
        } else {
          template += ', {{' + i + '}}';
        }
      }
      if (signature.returnTypeName !== 'void') {
        template += ' => {{?}}';
      }
      $('#pointcutSpanTemplate_' + pointcutNum).val(template);
    }

    function matchingMethods(methodName) {
      var url = 'pointcut/matching-methods?type-name=' + $('#pointcutTypeName_' + pointcutNum).val()
          + '&method-name=' + methodName;
      $.getJSON(url, function (signatures) {
        $('#pointcutMethodSignatures_' + pointcutNum).html('');
        $('#pointcutSpanTemplate_' + pointcutNum).val('');
        var html = '<div style="padding-top: 20px;">';
        var i;
        for (i = 0; i < signatures.length; i++) {
          html += '<div style="padding: 10px 0;">'
              + '<div class="radio">'
              + '<input type="radio" name="pointcutMethodSignature_' + pointcutNum + '" value="' + i
              + '">'
              + signatureText(signatures[i].modifiers, signatures[i].returnTypeName,
              signatures[i].name, signatures[i].argTypeNames)
              + '<br></div></div>';
        }
        html += '</div>';
        $('#pointcutMethodSignatures_' + pointcutNum).append(html);
        $('#pointcutMethodSignatures_' + pointcutNum).data('signatures', signatures);
        var $pointcutMethodSignatureRadio =
            $('input[type=radio][name=pointcutMethodSignature_' + pointcutNum + ']');
        $pointcutMethodSignatureRadio.change(function () {
          var span = $('#pointcutCaptureSpan_' + pointcutNum).is(':checked');
          if (span) {
            updateSpanTemplate();
          }
        });
        if (signatures.length === 1) {
          $pointcutMethodSignatureRadio.attr('checked', true);
          $pointcutMethodSignatureRadio.change();
        }
      });
    }

    function selectMethodName(methodName) {
      // since matchingMethods clears the span template, check here if the value has really
      // changed (e.g. that a user didn't start altering text and then changed mind and put the
      // previous value back)
      // also, this condition is needed in case where user clicks on typeahead value with mouse in
      // which case change event and typeahead event are called and this condition ensures that the
      // typeahead wins (because it runs first due to manually inserted delay in change event
      // handler)
      if (methodName !== $('#pointcutMethodName_' + pointcutNum).data('selectedValue')) {
        $('#pointcutMethodName_' + pointcutNum).data('selectedValue', methodName);
        matchingMethods(methodName);
      }
      return methodName;
    }

    function updateSectionHiding() {
      var metric = $('#pointcutCaptureMetric_' + pointcutNum).is(':checked');
      var span = $('#pointcutCaptureSpan_' + pointcutNum).is(':checked');
      var trace = $('#pointcutCaptureTrace_' + pointcutNum).is(':checked');
      if (metric) {
        $('#pointcutMetricSection_' + pointcutNum).removeClass('hide');
      } else {
        $('#pointcutMetricSection_' + pointcutNum).addClass('hide');
      }
      if (span || trace) {
        $('#pointcutSpanSection_' + pointcutNum).removeClass('hide');
      } else {
        $('#pointcutSpanSection_' + pointcutNum).addClass('hide');
      }
      if (span && $('#pointcutSpanTemplate_' + pointcutNum).val() === '') {
        // populate default template value on selecting span
        updateSpanTemplate();
      }
      if (!span) {
        // clear template value on de-selecting span
        $('#pointcutSpanTemplate_' + pointcutNum).val('');
      }
    }

    function getSignature() {
      var signatures = $('#pointcutMethodSignatures_' + pointcutNum).data('signatures');
      if (signatures.length === 1) {
        return signatures[0];
      }
      var selectedMethodSignature = $('input[type=radio][name=pointcutMethodSignature_'
          + pointcutNum + ']:checked');
      if (selectedMethodSignature.length === 0) {
        return undefined;
      }
      return signatures[selectedMethodSignature.val()];
    }

    function savePointcut() {
      var captureItems = [];
      if ($('#pointcutCaptureMetric_' + pointcutNum).is(':checked')) {
        captureItems.push('metric');
      }
      if ($('#pointcutCaptureSpan_' + pointcutNum).is(':checked')) {
        captureItems.push('span');
      }
      if ($('#pointcutCaptureTrace_' + pointcutNum).is(':checked')) {
        captureItems.push('trace');
      }
      var signature = getSignature();
      if (!signature) {
        // TODO handle this better
        alert('method for pointcut must be selected');
        return;
      }
      // methodReturnTypeName and methodModifiers are intentionally not included in pointcuts since
      // the method name and arg types are enough to uniquely identify the method, and further
      // restricting the pointcut based on return type and modifiers would make it brittle to slight
      // changes in the return type (e.g. narrowing) or modifiers on the method (e.g. visibility)
      var updatedPointcut = {
        'captureItems': captureItems,
        'typeName': $('#pointcutTypeName_' + pointcutNum).val(),
        'methodName': $('#pointcutMethodName_' + pointcutNum).val(),
        'methodArgTypeNames': signature.argTypeNames,
        'metricName': $('#pointcutMetricName_' + pointcutNum).val(),
        'spanTemplate': $('#pointcutSpanTemplate_' + pointcutNum).val()
      };
      var url;
      if (pointcut.version) {
        url = 'config/pointcut/' + pointcut.version;
      } else {
        url = 'config/pointcut/+';
      }
      $.post(url, JSON.stringify(updatedPointcut), function (response) {
        Informant.showAndFadeSuccessMessage('#pointcutSaveComplete_' + pointcutNum);
        pointcut = updatedPointcut;
        pointcut.version = response;
        fixLabels();
      });
    }

    function fixLabels() {
      if (pointcut.version) {
        $('#pointcutHeader_' + pointcutNum).text(pointcut.typeName + '.' + pointcut.methodName
            + '(' + pointcut.methodArgTypeNames.join(', ') + ')');
        $('#pointcutSaveButton_' + pointcutNum).text('Save');
        $('#pointcutSaveComplete_' + pointcutNum).text('Saved');
      } else {
        $('#pointcutHeader_' + pointcutNum).text('<New Pointcut>');
        $('#pointcutSaveButton_' + pointcutNum).text('Add');
        $('#pointcutSaveComplete_' + pointcutNum).text('Added');
      }
    }

    function addBehavior() {
      $('#pointcutCaptureMetric_' + pointcutNum).click(updateSectionHiding);
      $('#pointcutCaptureSpan_' + pointcutNum).click(updateSectionHiding);
      $('#pointcutCaptureTrace_' + pointcutNum).click(updateSectionHiding);
      $('#pointcutTypeName_' + pointcutNum).typeahead({ source: matchingTypeNames });
      // important to bind typeahead event handler before change handler below since handler logic
      // relies on it running first
      $('#pointcutMethodName_' + pointcutNum).typeahead({
        source: matchingMethodNames,
        updater: selectMethodName
      });
      $('#pointcutTypeName_' + pointcutNum).change(function () {
        // check if the value has really changed (e.g. that a user didn't start altering text and
        // then changed mind and put the previous value back)
        if ($(this).val() !== $(this).data('value')) {
          $(this).data('value', $(this).val());
          $('#pointcutMethodName_' + pointcutNum).val('');
          $('#pointcutMethodSignatures_' + pointcutNum).html('');
          $('#pointcutSpanTemplate_' + pointcutNum).val('');
        }
      });
      $('#pointcutMethodName_' + pointcutNum).change(function () {
        // just in case user types in a value and doesn't select from typeahead
        // but this also gets called if user selects typeahead with mouse (when the field loses
        // focus, before the typeahead gains input control)
        // so delay this action so that it runs after the typeahead in this case, at which time
        // $('#pointcutMethodName_' + pointcutNum).val() will be the value selected in the typeahead
        // instead of the partial value that the user typed
        setTimeout(function () {
          selectMethodName($('#pointcutMethodName_' + pointcutNum).val());
        }, 250);
      });
      $('#pointcutSaveButton_' + pointcutNum).click(function () {
        savePointcut();
      });
      $('#pointcutDeleteButton_' + pointcutNum).click(function () {
        if (pointcut.version) {
          $.post('/config/pointcut/-', JSON.stringify(pointcut.version), function () {
            // collapsing using accordion function, then removing completely
            $('#pointcutForm_' + pointcutNum).one('hidden', function () {
              $('#pointcut_' + pointcutNum).remove();
            });
            $('#pointcutForm_' + pointcutNum).collapse('hide');
          });
        } else {
          // collapsing using accordion function, then removing completely
          $('#pointcutForm_' + pointcutNum).one('hidden', function () {
            $('#pointcut_' + pointcutNum).remove();
          });
          $('#pointcutForm_' + pointcutNum).collapse('hide');
        }
      });
    }

    function addData() {
      $('#pointcutTypeName_' + pointcutNum).val(pointcut.typeName);
      $('#pointcutMethodName_' + pointcutNum).val(pointcut.methodName);
      $('#pointcutMethodSignatures_' + pointcutNum).html('<div style="padding-top: 20px;">'
          + pointcut.methodName + '(' + pointcut.methodArgTypeNames.join(', ') + ')</div>');
      $('#pointcutMethodSignatures_' + pointcutNum).data('signatures', [
        {
          argTypeNames: pointcut.methodArgTypeNames
        }
      ]);
      if (pointcut.captureItems.indexOf('metric') !== -1) {
        $('#pointcutCaptureMetric_' + pointcutNum).attr('checked', true);
      }
      if (pointcut.captureItems.indexOf('span') !== -1) {
        $('#pointcutCaptureSpan_' + pointcutNum).attr('checked', true);
      }
      if (pointcut.captureItems.indexOf('trace') !== -1) {
        $('#pointcutCaptureTrace_' + pointcutNum).attr('checked', true);
      }
      // TODO 'methodArgTypeNames': signature.argTypeNames,
      $('#pointcutMetricName_' + pointcutNum).val(pointcut.metricName);
      $('#pointcutSpanTemplate_' + pointcutNum).val(pointcut.spanTemplate);
      updateSectionHiding();
    }

    $('#pointcutAccordion').append(pointcutTemplate({ num: pointcutNum }));
    fixLabels();
    addBehavior();
    if (pointcut.version) {
      addData();
    } else {
      // display form immediately for new pointcut
      $('#pointcutToggle_' + pointcutNum).trigger('click');
      $('#pointcutTypeName_' + pointcutNum).focus();
    }
  }

  $(document).ready(function () {
    Informant.configureAjaxError();
    read();
    $('#pointcutNewButton button').click(function () {
      applyPointcutEditTemplate({});
    });
  });
}());