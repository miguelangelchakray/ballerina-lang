/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.ballerinalang.util.tracer;

import org.ballerinalang.bre.bvm.BLangVMErrors;
import org.ballerinalang.model.values.BMap;
import org.ballerinalang.model.values.BValue;
import org.ballerinalang.util.observability.BallerinaObserver;
import org.ballerinalang.util.observability.ObserverContext;

import java.util.HashMap;
import java.util.Map;

import static org.ballerinalang.util.observability.ObservabilityConstants.PROPERTY_BSTRUCT_ERROR;
import static org.ballerinalang.util.observability.ObservabilityConstants.PROPERTY_ERROR;
import static org.ballerinalang.util.observability.ObservabilityConstants.PROPERTY_ERROR_MESSAGE;
import static org.ballerinalang.util.observability.ObservabilityConstants.PROPERTY_TRACE_PROPERTIES;
import static org.ballerinalang.util.tracer.TraceConstants.KEY_SPAN;
import static org.ballerinalang.util.tracer.TraceConstants.LOG_ERROR_KIND_EXCEPTION;
import static org.ballerinalang.util.tracer.TraceConstants.LOG_EVENT_TYPE_ERROR;
import static org.ballerinalang.util.tracer.TraceConstants.LOG_KEY_ERROR_KIND;
import static org.ballerinalang.util.tracer.TraceConstants.LOG_KEY_EVENT_TYPE;
import static org.ballerinalang.util.tracer.TraceConstants.LOG_KEY_MESSAGE;

/**
 * Observe the runtime and start/stop tracing.
 */
public class BallerinaTracingObserver implements BallerinaObserver {

    @Override
    public void startServerObservation(ObserverContext observerContext) {
        BSpan span = new BSpan(observerContext, false);
        span.setConnectorName(observerContext.getServiceName());
        span.setActionName(observerContext.getResourceName());
        Map<String, String> httpHeaders = (Map<String, String>) observerContext.getProperty(PROPERTY_TRACE_PROPERTIES);
        if (httpHeaders != null) {
            httpHeaders.entrySet().stream()
                    .filter(c -> TraceConstants.TRACE_HEADER.equals(c.getKey()))
                    .forEach(e -> span.addProperty(e.getKey(), e.getValue()));
        }
        observerContext.addProperty(KEY_SPAN, span);
        span.startSpan();
    }

    @Override
    public void startClientObservation(ObserverContext observerContext) {
        BSpan activeSpan = new BSpan(observerContext, true);
        observerContext.addProperty(KEY_SPAN, activeSpan);
        activeSpan.setConnectorName(observerContext.getConnectorName());
        activeSpan.setActionName(observerContext.getActionName());
        observerContext.addProperty(PROPERTY_TRACE_PROPERTIES, activeSpan.getProperties());
        activeSpan.startSpan();
    }

    @Override
    public void stopServerObservation(ObserverContext observerContext) {
        stopObservation(observerContext);
    }

    @Override
    public void stopClientObservation(ObserverContext observerContext) {
        stopObservation(observerContext);
    }

    private void stopObservation(ObserverContext observerContext) {
        BSpan span = (BSpan) observerContext.getProperty(KEY_SPAN);
        if (span != null) {
            Boolean error = (Boolean) observerContext.getProperty(PROPERTY_ERROR);
            if (error != null && error) {
                StringBuilder errorMessageBuilder = new StringBuilder();
                String errorMessage = (String) observerContext.getProperty(PROPERTY_ERROR_MESSAGE);
                if (errorMessage != null) {
                    errorMessageBuilder.append(errorMessage);
                }
                BMap<String, BValue> bError =
                        (BMap<String, BValue>) observerContext.getProperty(PROPERTY_BSTRUCT_ERROR);
                if (bError != null) {
                    if (errorMessage != null) {
                        errorMessageBuilder.append('\n');
                    }
                    errorMessageBuilder.append(BLangVMErrors.getPrintableStackTrace(bError));
                }
                Map<String, Object> logProps = new HashMap<>();
                logProps.put(LOG_KEY_ERROR_KIND, LOG_ERROR_KIND_EXCEPTION);
                logProps.put(LOG_KEY_EVENT_TYPE, LOG_EVENT_TYPE_ERROR);
                logProps.put(LOG_KEY_MESSAGE, errorMessageBuilder.toString());
                span.logError(logProps);
            }
            span.addTags(observerContext.getTags());
            span.finishSpan();
        }
    }
}
