/*
 * Copyright 2016-2020 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin2.reporter.brave;

import brave.handler.MutableSpan;
import brave.handler.SpanHandler;
import brave.propagation.TraceContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.Before;
import org.junit.Test;
import zipkin2.Annotation;
import zipkin2.Endpoint;
import zipkin2.Span;
import zipkin2.reporter.Reporter;

import static brave.Span.Kind.CLIENT;
import static brave.Span.Kind.CONSUMER;
import static brave.Span.Kind.PRODUCER;
import static brave.Span.Kind.SERVER;
import static brave.handler.SpanHandler.Cause.ABANDONED;
import static brave.handler.SpanHandler.Cause.FINISHED;
import static brave.handler.SpanHandler.Cause.FLUSHED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

public class ZipkinSpanHandlerTest {
  List<Span> spans = new ArrayList<>();
  ZipkinSpanHandler handler;
  TraceContext context = TraceContext.newBuilder().traceId(1).spanId(2).sampled(true).build();
  MutableSpan defaultSpan;

  @Before public void init() {
    defaultSpan = new MutableSpan();
    defaultSpan.localServiceName("Aa");
    defaultSpan.localIp("1.2.3.4");
    defaultSpan.localPort(80);
    handler = (ZipkinSpanHandler) ZipkinSpanHandler.create(spans::add);
  }

  @Test public void generateKindMap() {
    assertThat(ZipkinSpanHandler.generateKindMap()).containsExactly(
        entry(CLIENT, Span.Kind.CLIENT),
        entry(SERVER, Span.Kind.SERVER),
        entry(PRODUCER, Span.Kind.PRODUCER),
        entry(CONSUMER, Span.Kind.CONSUMER)
    );
  }

  @Test public void noopIsNoop() {
    assertThat(ZipkinSpanHandler.create(Reporter.NOOP))
        .isSameAs(SpanHandler.NOOP);
  }

  @Test public void equalsAndHashCode() {
    assertThat(handler)
        .hasSameHashCodeAs(ZipkinSpanHandler.create(handler.spanReporter))
        .isEqualTo(ZipkinSpanHandler.create(handler.spanReporter));

    SpanHandler otherHandler = ZipkinSpanHandler.create(s -> {
    });

    assertThat(handler)
        .isNotEqualTo(otherHandler)
        .extracting(Objects::hashCode)
        .isNotEqualTo(otherHandler.hashCode());
  }

  @Test public void reportsSampledSpan() {
    MutableSpan span = new MutableSpan();
    handler.end(context, span, FINISHED);

    assertThat(spans.get(0)).isEqualToComparingFieldByField(
        Span.newBuilder()
            .traceId("1")
            .id("2")
            .build()
    );
  }

  @Test public void reportsDebugSpan() {
    TraceContext context = TraceContext.newBuilder().traceId(1).spanId(2).debug(true).build();
    MutableSpan span = new MutableSpan();
    handler.end(context, span, FINISHED);

    assertThat(spans.get(0)).isEqualToComparingFieldByField(
        Span.newBuilder()
            .traceId("1")
            .id("2")
            .debug(true)
            .build()
    );
  }

  @Test public void doesntReportUnsampledSpan() {
    TraceContext context =
        TraceContext.newBuilder().traceId(1).spanId(2).sampled(false).sampledLocal(true).build();
    handler.end(context, new MutableSpan(), FINISHED);

    assertThat(spans).isEmpty();
  }

  @Test public void abandonedDoesntReport() {
    handler.end(context, new MutableSpan(), ABANDONED);

    assertThat(spans).hasSize(0);
  }

  @Test public void alwaysReportSpans_reportsUnsampledSpan() {
    handler = (ZipkinSpanHandler) ZipkinSpanHandler.newBuilder(spans::add)
        .alwaysReportSpans(true)
        .build();

    TraceContext context =
        TraceContext.newBuilder().traceId(1).spanId(2).sampled(false).sampledLocal(true).build();
    handler.end(context, new MutableSpan(), FINISHED);

    assertThat(spans).isNotEmpty();
  }

  @Test public void minimumDurationIsOne() {
    MutableSpan span = new MutableSpan();

    span.startTimestamp(1L);
    span.finishTimestamp(1L);

    handler.end(context, span, FINISHED);
    assertThat(spans.get(0).duration()).isEqualTo(1L);
  }

  @Test public void replacesTag() {
    MutableSpan span = new MutableSpan();

    span.tag("1", "1");
    span.tag("foo", "bar");
    span.tag("2", "2");
    span.tag("foo", "baz");
    span.tag("3", "3");

    handler.end(context, span, FINISHED);
    assertThat(spans.get(0).tags()).containsOnly(
        entry("1", "1"),
        entry("foo", "baz"),
        entry("2", "2"),
        entry("3", "3")
    );
  }

  Throwable ERROR = new RuntimeException();

  @Test public void backfillsErrorTag() {
    MutableSpan span = new MutableSpan();

    span.error(ERROR);

    handler.end(context, span, FINISHED);

    assertThat(spans.get(0).tags())
        .containsOnly(entry("error", "RuntimeException"));
  }

  @Test public void doesntOverwriteErrorTag() {
    MutableSpan span = new MutableSpan();

    span.error(ERROR);
    span.tag("error", "");

    handler.end(context, span, FINISHED);

    assertThat(spans.get(0).tags())
        .containsOnly(entry("error", ""));
  }

  @Test public void addsAnnotations() {
    MutableSpan span = new MutableSpan();

    span.startTimestamp(1L);
    span.annotate(2L, "foo");
    span.finishTimestamp(2L);

    handler.end(context, span, FINISHED);

    assertThat(spans.get(0).annotations())
        .containsOnly(Annotation.create(2L, "foo"));
  }

  @Test public void finished_client() {
    finish(brave.Span.Kind.CLIENT, Span.Kind.CLIENT);
  }

  @Test public void finished_server() {
    finish(brave.Span.Kind.SERVER, Span.Kind.SERVER);
  }

  @Test public void finished_producer() {
    finish(brave.Span.Kind.PRODUCER, Span.Kind.PRODUCER);
  }

  @Test public void finished_consumer() {
    finish(brave.Span.Kind.CONSUMER, Span.Kind.CONSUMER);
  }

  void finish(brave.Span.Kind braveKind, Span.Kind span2Kind) {
    MutableSpan span = new MutableSpan();
    span.kind(braveKind);
    span.startTimestamp(1L);
    span.finishTimestamp(2L);

    handler.end(context, span, FINISHED);

    Span zipkinSpan = spans.get(0);
    assertThat(zipkinSpan.annotations()).isEmpty();
    assertThat(zipkinSpan.timestamp()).isEqualTo(1L);
    assertThat(zipkinSpan.duration()).isEqualTo(1L);
    assertThat(zipkinSpan.kind()).isEqualTo(span2Kind);
  }

  @Test public void flushed_client() {
    flush(brave.Span.Kind.CLIENT, Span.Kind.CLIENT);
  }

  @Test public void flushed_server() {
    flush(brave.Span.Kind.SERVER, Span.Kind.SERVER);
  }

  @Test public void flushed_producer() {
    flush(brave.Span.Kind.PRODUCER, Span.Kind.PRODUCER);
  }

  @Test public void flushed_consumer() {
    flush(brave.Span.Kind.CONSUMER, Span.Kind.CONSUMER);
  }

  void flush(brave.Span.Kind braveKind, Span.Kind span2Kind) {
    MutableSpan span = new MutableSpan();
    span.kind(braveKind);
    span.startTimestamp(1L);
    span.finishTimestamp(0L);

    handler.end(context, span, FINISHED);

    Span zipkinSpan = spans.get(0);
    assertThat(zipkinSpan.annotations()).isEmpty();
    assertThat(zipkinSpan.timestamp()).isEqualTo(1L);
    assertThat(zipkinSpan.duration()).isNull();
    assertThat(zipkinSpan.kind()).isEqualTo(span2Kind);
  }

  @Test public void remoteEndpoint() {
    MutableSpan span = new MutableSpan();

    Endpoint endpoint = Endpoint.newBuilder()
        .serviceName("fooService")
        .ip("1.2.3.4")
        .port(80)
        .build();

    span.kind(CLIENT);
    span.remoteServiceName(endpoint.serviceName());
    span.remoteIpAndPort(endpoint.ipv4(), endpoint.port());
    span.startTimestamp(1L);
    span.finishTimestamp(2L);

    handler.end(context, span, FINISHED);

    assertThat(spans.get(0).remoteEndpoint())
        .isEqualTo(endpoint);
  }

  // This prevents the server startTimestamp from overwriting the client one on the collector
  @Test public void writeTo_sharedStatus() {
    MutableSpan span = new MutableSpan();

    span.setShared();
    span.startTimestamp(1L);
    span.kind(SERVER);
    span.finishTimestamp(2L);

    handler.end(context, span, FINISHED);

    assertThat(spans.get(0).shared())
        .isTrue();
  }

  @Test public void flushUnstartedNeitherSetsTimestampNorDuration() {
    MutableSpan flushed = new MutableSpan();
    flushed.finishTimestamp(0L);

    handler.end(context, flushed, FLUSHED);

    assertThat(spans.get(0)).extracting(Span::timestampAsLong, Span::durationAsLong)
        .allSatisfy(u -> assertThat(u).isEqualTo(0L));
  }
}