/*
 * Copyright 2021 VMware, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micrometer.dynatrace.v2;

import com.dynatrace.metric.util.*;
import io.micrometer.common.util.StringUtils;
import io.micrometer.common.util.internal.logging.InternalLogger;
import io.micrometer.common.util.internal.logging.InternalLoggerFactory;
import io.micrometer.common.util.internal.logging.WarnThenDebugLogger;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import io.micrometer.core.instrument.util.AbstractPartition;
import io.micrometer.core.ipc.http.HttpSender;
import io.micrometer.dynatrace.AbstractDynatraceExporter;
import io.micrometer.dynatrace.DynatraceConfig;
import io.micrometer.dynatrace.types.DynatraceSummarySnapshot;
import io.micrometer.dynatrace.types.DynatraceSummarySnapshotSupport;

import java.net.MalformedURLException;
import java.net.URI;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Implementation for Dynatrace v2 metrics API export.
 *
 * @author Georg Pirklbauer
 * @author Jonatan Ivanov
 * @since 1.8.0
 */
public final class DynatraceExporterV2 extends AbstractDynatraceExporter {

    private static final String METER_EXCEPTION_LOG_FORMAT = "Could not serialize meter {}: {}";

    private static final Pattern EXTRACT_LINES_OK = Pattern.compile("\"linesOk\":\\s?(\\d+)");

    private static final Pattern EXTRACT_LINES_INVALID = Pattern.compile("\"linesInvalid\":\\s?(\\d+)");

    private static final Pattern IS_NULL_ERROR_RESPONSE = Pattern.compile("\"error\":\\s?null");

    private static final WarnThenDebugLogger warnThenDebugLogger = new WarnThenDebugLogger(DynatraceExporterV2.class);

    private static final Map<String, String> staticDimensions = Collections.singletonMap("dt.metrics.source",
            "micrometer");

    // This should be non-static for MockLoggerFactory.injectLogger() in tests.
    private final InternalLogger logger = InternalLoggerFactory.getInstance(DynatraceExporterV2.class);

    private final MetricBuilderFactory metricBuilderFactory;

    public DynatraceExporterV2(DynatraceConfig config, Clock clock, HttpSender httpClient) {
        super(config, clock, httpClient);

        logger.info("Exporting to endpoint {}", config.uri());

        MetricBuilderFactory.MetricBuilderFactoryBuilder factoryBuilder = MetricBuilderFactory.builder()
                .withPrefix(config.metricKeyPrefix())
                .withDefaultDimensions(parseDefaultDimensions(config.defaultDimensions()));

        if (config.enrichWithDynatraceMetadata()) {
            factoryBuilder.withDynatraceMetadata();
        }

        metricBuilderFactory = factoryBuilder.build();
    }

    private boolean isValidEndpoint(String uri) {
        try {
            // noinspection ResultOfMethodCallIgnored
            URI.create(uri).toURL();
        }
        catch (IllegalArgumentException | MalformedURLException ex) {
            return false;
        }

        return true;
    }

    private boolean shouldIgnoreToken(DynatraceConfig config) {
        if (config.apiToken().isEmpty()) {
            return true;
        }
        if (config.uri().equals(DynatraceMetricApiConstants.getDefaultOneAgentEndpoint())) {
            logger.warn(
                    "Potential misconfiguration detected: Token is provided, but the endpoint is set to the local OneAgent endpoint, "
                            + "thus the token will be ignored. If exporting to the cluster API endpoint is intended, its URI has to be provided explicitly.");
            return true;
        }
        return false;
    }

    private DimensionList parseDefaultDimensions(Map<String, String> defaultDimensions) {
        List<Dimension> dimensions = Stream
                .concat(defaultDimensions.entrySet().stream(), staticDimensions.entrySet().stream())
                .map(entry -> Dimension.create(entry.getKey(), entry.getValue())).collect(Collectors.toList());
        return DimensionList.fromCollection(dimensions);
    }

    /**
     * Export to the Dynatrace v2 endpoint. Measurements that contain NaN or Infinite
     * values, as well as serialized data points that exceed length limits imposed by the
     * API will be dropped and not exported. If the number of serialized data points
     * exceeds the maximum number of allowed data points per request they will be sent in
     * chunks.
     * @param meters A list of {@link Meter Meters} that are serialized as one or more
     * metric lines.
     */
    @Override
    public void export(List<Meter> meters) {
        // Lines that are too long to be ingested into Dynatrace, as well as lines that
        // contain NaN
        // or Inf values are dropped and not returned from "toMetricLines", and are
        // therefore dropped.
        List<String> metricLines = meters.stream().flatMap(this::toMetricLines) // Stream<Meter>
                                                                                // to
                                                                                // Stream<String>
                .collect(Collectors.toList());

        sendInBatches(metricLines);
    }

    private Stream<String> toMetricLines(Meter meter) {
        return meter.match(this::toGaugeLine, this::toCounterLine, this::toTimerLine, this::toDistributionSummaryLine,
                this::toLongTaskTimerLine, this::toTimeGaugeLine, this::toFunctionCounterLine,
                this::toFunctionTimerLine, this::toMeterLine);
    }

    Stream<String> toGaugeLine(Gauge meter) {
        return toMeterLine(meter, this::createGaugeLine);
    }

    private String createGaugeLine(Meter meter, Measurement measurement) {
        try {
            return createMetricBuilder(meter).setDoubleGaugeValue(measurement.getValue()).serialize();
        }
        catch (MetricException e) {
            logger.warn(METER_EXCEPTION_LOG_FORMAT, meter.getId().getName(), e.getMessage());
        }

        return null;
    }

    Stream<String> toCounterLine(Counter meter) {
        return toMeterLine(meter, this::createCounterLine);
    }

    private String createCounterLine(Meter meter, Measurement measurement) {
        try {
            return createMetricBuilder(meter).setDoubleCounterValueDelta(measurement.getValue()).serialize();
        }
        catch (MetricException e) {
            logger.warn(METER_EXCEPTION_LOG_FORMAT, meter.getId().getName(), e.getMessage());
        }

        return null;
    }

    Stream<String> toTimerLine(Timer meter) {
        if (!(meter instanceof DynatraceSummarySnapshotSupport)) {
            // fall back to previous behaviour
            return toSummaryLine(meter, meter.takeSnapshot(), getBaseTimeUnit());
        }

        DynatraceSummarySnapshotSupport summary = (DynatraceSummarySnapshotSupport) meter;
        if (!summary.hasValues()) {
            return Stream.empty();
        }

        // Take a snapshot and reset the Timer for the next export
        DynatraceSummarySnapshot snapshot = summary.takeSummarySnapshotAndReset(getBaseTimeUnit());
        return createSummaryLine(meter, snapshot.getMin(), snapshot.getMax(), snapshot.getTotal(), snapshot.getCount());
    }

    private Stream<String> toSummaryLine(Meter meter, HistogramSnapshot histogramSnapshot, TimeUnit timeUnit) {
        long count = histogramSnapshot.count();
        if (count < 1) {
            logger.debug("Summary with 0 count dropped: {}", meter.getId().getName());
            return Stream.empty();
        }
        double total = (timeUnit != null) ? histogramSnapshot.total(timeUnit) : histogramSnapshot.total();
        double max = (timeUnit != null) ? histogramSnapshot.max(timeUnit) : histogramSnapshot.max();
        double min = (count == 1) ? max : minFromHistogramSnapshot(histogramSnapshot, timeUnit);
        return createSummaryLine(meter, min, max, total, count);
    }

    private double minFromHistogramSnapshot(HistogramSnapshot histogramSnapshot, TimeUnit timeUnit) {
        ValueAtPercentile[] valuesAtPercentiles = histogramSnapshot.percentileValues();
        for (ValueAtPercentile valueAtPercentile : valuesAtPercentiles) {
            if (valueAtPercentile.percentile() == 0.0) {
                return (timeUnit != null) ? valueAtPercentile.value(timeUnit) : valueAtPercentile.value();
            }
        }
        return Double.NaN;
    }

    private Stream<String> createSummaryLine(Meter meter, double min, double max, double total, long count) {
        try {
            String line = createMetricBuilder(meter).setDoubleSummaryValue(min, max, total, count).serialize();
            return Stream.of(line);
        }
        catch (MetricException e) {
            logger.warn(METER_EXCEPTION_LOG_FORMAT, meter.getId().getName(), e.getMessage());
        }

        return Stream.empty();
    }

    Stream<String> toDistributionSummaryLine(DistributionSummary meter) {
        if (!(meter instanceof DynatraceSummarySnapshotSupport)) {
            // fall back to previous behaviour
            return toSummaryLine(meter, meter.takeSnapshot(), null);
        }

        DynatraceSummarySnapshotSupport summary = (DynatraceSummarySnapshotSupport) meter;

        if (!summary.hasValues()) {
            return Stream.empty();
        }

        // Take a snapshot and reset the DistributionSummary for the next export
        DynatraceSummarySnapshot snapshot = ((DynatraceSummarySnapshotSupport) meter).takeSummarySnapshotAndReset();
        return createSummaryLine(meter, snapshot.getMin(), snapshot.getMax(), snapshot.getTotal(), snapshot.getCount());
    }

    Stream<String> toLongTaskTimerLine(LongTaskTimer meter) {
        return toSummaryLine(meter, meter.takeSnapshot(), getBaseTimeUnit());
    }

    Stream<String> toTimeGaugeLine(TimeGauge meter) {
        return toMeterLine(meter, this::createGaugeLine);
    }

    Stream<String> toFunctionCounterLine(FunctionCounter meter) {
        return toMeterLine(meter, this::createCounterLine);
    }

    Stream<String> toFunctionTimerLine(FunctionTimer meter) {
        long count = (long) meter.count();
        if (count < 1) {
            logger.debug("Timer with 0 count dropped: {}", meter.getId().getName());
            return Stream.empty();
        }
        double total = meter.totalTime(getBaseTimeUnit());
        double average = meter.mean(getBaseTimeUnit());

        return createSummaryLine(meter, average, average, total, count);
    }

    Stream<String> toMeterLine(Meter meter) {
        return toMeterLine(meter, this::createGaugeLine);
    }

    private Stream<String> toMeterLine(Meter meter, BiFunction<Meter, Measurement, String> measurementConverter) {
        return streamOf(meter.measure()).map(measurement -> measurementConverter.apply(meter, measurement))
                .filter(Objects::nonNull);
    }

    private Metric.Builder createMetricBuilder(Meter meter) {
        return metricBuilderFactory.newMetricBuilder(meter.getId().getName())
                .setDimensions(fromTags(meter.getId().getTags())).setTimestamp(Instant.ofEpochMilli(clock.wallTime()));
    }

    private DimensionList fromTags(List<Tag> tags) {
        return DimensionList.fromCollection(
                tags.stream().map(tag -> Dimension.create(tag.getKey(), tag.getValue())).collect(Collectors.toList()));
    }

    private <T> Stream<T> streamOf(Iterable<T> iterable) {
        return StreamSupport.stream(iterable.spliterator(), false);
    }

    private void send(List<String> metricLines) {
        String endpoint = config.uri();
        if (!isValidEndpoint(endpoint)) {
            logger.warn("Invalid endpoint, skipping export... ({})", endpoint);
            return;
        }
        try {
            logger.debug("Sending {} lines to {}", metricLines.size(), endpoint);

            String body = String.join("\n", metricLines);
            logger.debug("Sending lines:\n{}", body);

            HttpSender.Request.Builder requestBuilder = httpClient.post(endpoint);
            if (!shouldIgnoreToken(config)) {
                requestBuilder.withHeader("Authorization", "Api-Token " + config.apiToken());
            }

            requestBuilder.withHeader("User-Agent", "micrometer").withPlainText(body).send()
                    .onSuccess(response -> handleSuccess(metricLines.size(), response))
                    .onError(response -> logger.error("Failed metric ingestion: Error Code={}, Response Body={}",
                            response.code(), getTruncatedBody(response)));
        }
        catch (Throwable throwable) {
            logger.warn("Failed metric ingestion: " + throwable);
            warnThenDebugLogger.log("Stack trace for previous 'Failed metric ingestion' warning log: ", throwable);
        }
    }

    private String getTruncatedBody(HttpSender.Response response) {
        return StringUtils.truncate(response.body(), 1_000, " (truncated)");
    }

    private void handleSuccess(int totalSent, HttpSender.Response response) {
        if (response.code() == 202) {
            if (IS_NULL_ERROR_RESPONSE.matcher(response.body()).find()) {
                Matcher linesOkMatchResult = EXTRACT_LINES_OK.matcher(response.body());
                Matcher linesInvalidMatchResult = EXTRACT_LINES_INVALID.matcher(response.body());
                if (linesOkMatchResult.find() && linesInvalidMatchResult.find()) {
                    logger.debug("Sent {} metric lines, linesOk: {}, linesInvalid: {}.", totalSent,
                            linesOkMatchResult.group(1), linesInvalidMatchResult.group(1));
                }
                else {
                    logger.warn("Unable to parse response: {}", getTruncatedBody(response));
                }
            }
            else {
                logger.warn("Unable to parse response: {}", getTruncatedBody(response));
            }
        }
        else {
            // common pitfall if URI is supplied in V1 format (without endpoint path)
            logger.error(
                    "Expected status code 202, got {}.\nResponse Body={}\nDid you specify the ingest path (e.g.: /api/v2/metrics/ingest)?",
                    response.code(), getTruncatedBody(response));
        }
    }

    private void sendInBatches(List<String> metricLines) {
        int partitionSize = Math.min(config.batchSize(), DynatraceMetricApiConstants.getPayloadLinesLimit());
        MetricLinePartition.partition(metricLines, partitionSize).forEach(this::send);
    }

    static class MetricLinePartition extends AbstractPartition<String> {

        private MetricLinePartition(List<String> list, int partitionSize) {
            super(list, partitionSize);
        }

        static List<List<String>> partition(List<String> list, int partitionSize) {
            return new MetricLinePartition(list, partitionSize);
        }

    }

}
