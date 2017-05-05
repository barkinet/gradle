/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.logging.console;

import com.google.common.collect.Lists;
import org.gradle.api.Nullable;
import org.gradle.api.logging.LogLevel;
import org.gradle.internal.SystemProperties;
import org.gradle.internal.logging.events.BatchOutputEventListener;
import org.gradle.internal.logging.events.CategorisedOutputEvent;
import org.gradle.internal.logging.events.EndOutputEvent;
import org.gradle.internal.logging.events.LogEvent;
import org.gradle.internal.logging.events.OperationIdentifier;
import org.gradle.internal.logging.events.OutputEvent;
import org.gradle.internal.logging.events.ProgressCompleteEvent;
import org.gradle.internal.logging.events.ProgressEvent;
import org.gradle.internal.logging.events.ProgressStartEvent;
import org.gradle.internal.logging.events.RenderableOutputEvent;
import org.gradle.internal.logging.events.StyledTextOutputEvent;
import org.gradle.internal.logging.text.StyledTextOutput;
import org.gradle.internal.progress.BuildOperationType;
import org.gradle.internal.time.TimeProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class LogGroupingOutputEventListener extends BatchOutputEventListener {
    static final int SCHEDULER_CHECK_PERIOD_MS = 5000;
    public static final String EOL = SystemProperties.getInstance().getLineSeparator();
    private final BatchOutputEventListener listener;
    private final ScheduledExecutorService executor;
    private final TimeProvider timeProvider;
    private final Object lock = new Object();

    // Allows us to map progress and complete events to start events
    private final Map<OperationIdentifier, Object> progressIdToBuildOperationIdMap = new LinkedHashMap<OperationIdentifier, Object>();

    // Maintain a hierarchy of all build operation ids, so that child operations are grouped under their ancestor
    private final Map<Object, Object> buildOperationIdForest = new LinkedHashMap<Object, Object>();

    // Event groups that are in-progress and have not been completed
    private final Map<Object, ArrayList<CategorisedOutputEvent>> outputEventGroups = new LinkedHashMap<Object, ArrayList<CategorisedOutputEvent>>();
    private Object lastRenderedOperationId;
    private boolean needsHeader;

    public LogGroupingOutputEventListener(BatchOutputEventListener listener, TimeProvider timeProvider) {
        this(listener, Executors.newSingleThreadScheduledExecutor(), timeProvider);
    }

    LogGroupingOutputEventListener(BatchOutputEventListener listener, ScheduledExecutorService executor, TimeProvider timeProvider) {
        this.listener = listener;
        this.executor = executor;
        this.timeProvider = timeProvider;
    }

    @Override
    public void onOutput(OutputEvent event) {
        synchronized (lock) {
            if (event instanceof EndOutputEvent) {
                renderAllGroups(outputEventGroups);
                listener.onOutput(event);
//                executor.shutdown();
            } else if (event instanceof ProgressStartEvent) {
                onStart((ProgressStartEvent) event);
            } else if (event instanceof ProgressEvent) {
                ProgressEvent progressEvent = (ProgressEvent) event;
                groupOrForward(progressIdToBuildOperationIdMap.get(progressEvent.getProgressOperationId()), progressEvent);
            } else if (event instanceof RenderableOutputEvent) {
                RenderableOutputEvent renderableOutputEvent = (RenderableOutputEvent) event;
                groupOrForward(renderableOutputEvent.getBuildOperationId(), renderableOutputEvent);
            } else if (event instanceof ProgressCompleteEvent) {
                onComplete((ProgressCompleteEvent) event);
            } else {
                listener.onOutput(event);
            }

            // TODO(ew): Use strategy from ThrottlingOEL to schedule runnable once every 5 seconds on new output
//            executor.schedule(new Runnable() {
//                @Override
//                public void run() {
//                    synchronized (lock) {
//                        renderAllGroups(outputEventGroups);
//                    }
//                }
//            }, SCHEDULER_CHECK_PERIOD_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void onStart(ProgressStartEvent event) {
        Object buildOpId = event.getBuildOperationId();
        if (buildOpId != null) {
            buildOperationIdForest.put(buildOpId, event.getParentBuildOperationId());
            progressIdToBuildOperationIdMap.put(event.getProgressOperationId(), buildOpId);

            if (event.getBuildOperationType() == BuildOperationType.TASK || event.getBuildOperationType() == BuildOperationType.CONFIGURE_PROJECT) {
                // TODO: check whether child of another task
                List<StyledTextOutputEvent.Span> header = Collections.singletonList(makeHeader(event));
                CategorisedOutputEvent headerEvent = new StyledTextOutputEvent(event.getTimestamp(), event.getCategory(), LogLevel.QUIET, buildOpId, header);
                outputEventGroups.put(buildOpId, Lists.newArrayList(headerEvent, event));
            } else {
                groupOrForward(buildOpId, event);
            }
        } else {
            listener.onOutput(event);
        }
    }

    private StyledTextOutputEvent.Span makeHeader(ProgressStartEvent event) {
        final String message;
        if (event.getLoggingHeader() != null) {
            message = event.getLoggingHeader();
        } else if (event.getShortDescription() != null) {
            message = event.getShortDescription();
        } else {
            message = event.getDescription();
        }
        return new StyledTextOutputEvent.Span(StyledTextOutput.Style.Header, "> " + message + EOL);
    }

    private void onComplete(ProgressCompleteEvent event) {
        Object buildOpId = progressIdToBuildOperationIdMap.get(event.getProgressOperationId());
        Object groupId;

        if (outputEventGroups.containsKey(buildOpId)) {
            // Render group if complete
            List<OutputEvent> group = new ArrayList<OutputEvent>(outputEventGroups.remove(buildOpId));
            group.add(event);
            renderGroup(buildOpId, group);
        } else if ((groupId = getGroupId(buildOpId)) != null) {
            // Add to group if possible
            outputEventGroups.get(groupId).add(event);
        } else {
            // Otherwise just forward the event
            listener.onOutput(event);
        }
    }

    // Return the id of the group, checking up the build operation id hierarchy
    // We are assuming that the average height of the build operation id forest is very low
    private Object getGroupId(@Nullable final Object buildOperationId) {
        Object current = buildOperationId;
        while (current != null) {
            // TODO(ew): for composite builds, may need to group by highest TASK parent
            if (outputEventGroups.containsKey(current)) {
                return current;
            }
            current = buildOperationIdForest.get(current);
        }
        return null;
    }

    private void groupOrForward(Object buildOpId, CategorisedOutputEvent event) {
        Object groupId = getGroupId(buildOpId);
        if (groupId != null) {
            outputEventGroups.get(groupId).add(event);
        } else {
            if (event instanceof RenderableOutputEvent) {
                needsHeader = true;
            }
            listener.onOutput(event);
        }
    }

    /**
     * return true if any event is a RenderableOutputEvent.
     */
    private boolean hasRenderableEvents(Iterable<OutputEvent> events) {
        int index = 0;
        for (OutputEvent event : events) {
            if (index++ > 0 && event instanceof RenderableOutputEvent) {
                return true;
            }
        }
        return false;
    }

    private boolean renderGroup(Object buildOpId, List<OutputEvent> group) {
        if (hasRenderableEvents(group)) {
            // Visually indicate group by adding surrounding lines
            if (needsHeader) {
                renderNewLine();
                needsHeader = false;
            }

            listener.onOutput(group);

            // Visually indicate a new group by adding a line if not appending to last rendered group
            if (!buildOpId.equals(lastRenderedOperationId)) {
                renderNewLine();
            }
            lastRenderedOperationId = buildOpId;
            return true;
        }
        return false;
    }

    private void renderNewLine() {
        listener.onOutput(new LogEvent(timeProvider.getCurrentTime(), LogGroupingOutputEventListener.class.toString(), LogLevel.QUIET, "", null));
    }

    private void renderAllGroups(Map<Object, ArrayList<CategorisedOutputEvent>> groups) {
        for (Map.Entry<Object, ArrayList<CategorisedOutputEvent>> entry : groups.entrySet()) {
            ArrayList<OutputEvent> group = new ArrayList<OutputEvent>(entry.getValue());
            if (renderGroup(entry.getKey(), group)) {
                // Preserve header
                entry.setValue(Lists.newArrayList((CategorisedOutputEvent) group.get(0)));
            }
        }
    }
}
