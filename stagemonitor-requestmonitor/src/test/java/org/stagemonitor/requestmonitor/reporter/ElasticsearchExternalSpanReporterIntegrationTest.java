package org.stagemonitor.requestmonitor.reporter;

import com.fasterxml.jackson.databind.JsonNode;
import com.uber.jaeger.reporters.LoggingReporter;
import com.uber.jaeger.samplers.ConstSampler;

import org.junit.Before;
import org.junit.Test;
import org.stagemonitor.core.CorePlugin;
import org.stagemonitor.core.configuration.AbstractElasticsearchTest;
import org.stagemonitor.core.configuration.Configuration;
import org.stagemonitor.core.metrics.metrics2.Metric2Registry;
import org.stagemonitor.core.util.IOUtils;
import org.stagemonitor.requestmonitor.RequestMonitorPlugin;
import org.stagemonitor.requestmonitor.SpanContextInformation;

import java.util.concurrent.TimeUnit;

import io.opentracing.tag.Tags;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ElasticsearchExternalSpanReporterIntegrationTest extends AbstractElasticsearchTest {

	protected ElasticsearchSpanReporter reporter;
	protected RequestMonitorPlugin requestMonitorPlugin;
	protected Configuration configuration;
	private com.uber.jaeger.Tracer tracer;

	@Before
	public void setUp() throws Exception {
		this.configuration = mock(Configuration.class);
		this.requestMonitorPlugin = mock(RequestMonitorPlugin.class);
		when(configuration.getConfig(CorePlugin.class)).thenReturn(corePlugin);
		when(configuration.getConfig(RequestMonitorPlugin.class)).thenReturn(requestMonitorPlugin);
		when(corePlugin.getElasticsearchClient()).thenReturn(elasticsearchClient);
		when(corePlugin.getMetricRegistry()).thenReturn(new Metric2Registry());
		when(requestMonitorPlugin.getRateLimitClientSpansPerMinute()).thenReturn(1000000d);
		reporter = new ElasticsearchSpanReporter();
		reporter.init(configuration);
		final String mappingTemplate = IOUtils.getResourceAsString("stagemonitor-elasticsearch-span-index-template.json");
		elasticsearchClient.sendMappingTemplateAsync(mappingTemplate, "stagemonitor-spans");
		elasticsearchClient.waitForCompletion();
		tracer = new com.uber.jaeger.Tracer.Builder("ElasticsearchExternalSpanReporterIntegrationTest", new LoggingReporter(), new ConstSampler(true)).build();

	}

	@Test
	public void reportTemplateCreated() throws Exception {
		final JsonNode template = elasticsearchClient.getJson("/_template/stagemonitor-spans").get("stagemonitor-spans");
		assertEquals("stagemonitor-spans-*", template.get("template").asText());
		assertEquals(false, template.get("mappings").get("_default_").get("_all").get("enabled").asBoolean());
	}

	@Test
	public void reportSpan() throws Exception {
		reporter.report(SpanContextInformation.forUnitTest(getSpan(100)));
		elasticsearchClient.waitForCompletion();
		refresh();
		final JsonNode hits = elasticsearchClient.getJson("/stagemonitor-spans*/_search").get("hits");
		assertEquals(1, hits.get("total").intValue());
		final JsonNode spanJson = hits.get("hits").elements().next().get("_source");
		assertEquals("jdbc", spanJson.get("type").asText());
		assertEquals("SELECT", spanJson.get("method").asText());
		assertEquals(100000, spanJson.get("duration").asInt());
		assertEquals("SELECT * from STAGEMONITOR where 1 < 2", spanJson.get("db").get("statement").asText());
		assertEquals("ElasticsearchExternalSpanReporterIntegrationTest#test", spanJson.get("name").asText());
	}

	private ReadbackSpan getSpan(long executionTimeMillis) {
		final ReadbackSpan readbackSpan = new ReadbackSpan();
		readbackSpan.setName("ElasticsearchExternalSpanReporterIntegrationTest#test");
		readbackSpan.setDuration(TimeUnit.MILLISECONDS.toMicros(executionTimeMillis));
		readbackSpan.setTag("type", "jdbc");
		readbackSpan.setTag("method", "SELECT");
		readbackSpan.setTag("db.statement", "SELECT * from STAGEMONITOR where 1 < 2");
		readbackSpan.setTag(Tags.PEER_SERVICE.getKey(), "foo@jdbc:bar");
		return readbackSpan;
	}
}
