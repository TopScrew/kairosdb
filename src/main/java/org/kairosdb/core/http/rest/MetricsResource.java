/*
 * Copyright 2016 KairosDB Authors
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package org.kairosdb.core.http.rest;


import com.google.common.annotations.VisibleForTesting;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.MalformedJsonException;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import org.kairosdb.core.DataPointSet;
import org.kairosdb.core.KairosDataPointFactory;
import org.kairosdb.core.datapoints.LongDataPointFactory;
import org.kairosdb.core.datapoints.LongDataPointFactoryImpl;
import org.kairosdb.core.datapoints.StringDataPointFactory;
import org.kairosdb.core.datastore.*;
import org.kairosdb.core.exception.DatastoreException;
import org.kairosdb.core.exception.InvalidServerTypeException;
import org.kairosdb.core.formatter.DataFormatter;
import org.kairosdb.core.formatter.FormatterException;
import org.kairosdb.core.formatter.JsonFormatter;
import org.kairosdb.core.formatter.JsonResponse;
import org.kairosdb.core.http.rest.json.*;
import org.kairosdb.core.reporting.KairosMetricReporter;
import org.kairosdb.core.reporting.ThreadReporter;
import org.kairosdb.eventbus.FilterEventBus;
import org.kairosdb.eventbus.Publisher;
import org.kairosdb.events.DataPointEvent;
import org.kairosdb.util.MemoryMonitorException;
import org.kairosdb.util.SimpleStats;
import org.kairosdb.util.SimpleStatsReporter;
import org.kairosdb.util.StatsMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.*;
import java.io.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static javax.ws.rs.core.Response.ResponseBuilder;

enum NameType
{
	METRIC_NAMES,
	TAG_KEYS,
	TAG_VALUES
}

enum ServerType
{
	INGEST,
	QUERY,
	DELETE
}

@Path("/api/v1")
public class MetricsResource implements KairosMetricReporter
{
	public static final Logger logger = LoggerFactory.getLogger(MetricsResource.class);
	public static final String QUERY_TIME = "kairosdb.http.query_time";
	public static final String REQUEST_TIME = "kairosdb.http.request_time";
	public static final String INGEST_COUNT = "kairosdb.http.ingest_count";
	public static final String INGEST_TIME = "kairosdb.http.ingest_time";

	public static final String QUERY_URL = "/datapoints/query";

	private final KairosDatastore datastore;
	private final Publisher<DataPointEvent> m_publisher;
	private final Map<String, DataFormatter> formatters = new HashMap<>();
	private final QueryParser queryParser;

	//Used for parsing incoming metrics
	private final Gson gson;

	//These two are used to track rate of ingestion
	private final AtomicInteger m_ingestedDataPoints = new AtomicInteger();
	private final AtomicInteger m_ingestTime = new AtomicInteger();

	private final StatsMap m_statsMap = new StatsMap();
	private final KairosDataPointFactory m_kairosDataPointFactory;

	@Inject
	private LongDataPointFactory m_longDataPointFactory = new LongDataPointFactoryImpl();

	@Inject
	private StringDataPointFactory m_stringDataPointFactory = new StringDataPointFactory();

	@Inject(optional = true)
	private QueryPreProcessorContainer m_queryPreProcessor = new QueryPreProcessorContainer()
	{
		@Override
		public Query preProcess(Query query)
		{
			return query;
		}
	};

	@Inject(optional = true)
	@Named("kairosdb.queries.aggregate_stats")
	private boolean m_aggregatedQueryMetrics = false;

	@Inject(optional = true)
	@Named("kairosdb.log.queries.enable")
	private boolean m_logQueries = false;

	@Inject(optional = true)
	@Named("kairosdb.log.queries.ttl")
	private int m_logQueriesTtl = 86400;

	@Inject(optional = true)
	@Named("kairosdb.log.queries.greater_than")
	private int m_logQueriesLongerThan = 60;

	@Inject
	@Named("HOSTNAME")
	private String hostName = "localhost";

	@Inject
	@Named("kairosdb.queries.return_query_in_response")
	private boolean m_returnQueryInResponse = false;

	//Used for setting which API methods are enabled
	private EnumSet<ServerType> m_serverType = EnumSet.of(ServerType.INGEST, ServerType.QUERY, ServerType.DELETE);

	@Inject(optional = true)
	@VisibleForTesting
	void setServerType(@Named("kairosdb.server.type") String serverType)
	{
		if (serverType.equals("ALL")) return;
		String serverTypeString = serverType.replaceAll("\\s+","");
		List<String> serverTypeList = Arrays.asList(serverTypeString.split(","));

		m_serverType = EnumSet.noneOf(ServerType.class);

		for (String stString : serverTypeList)
		{
			m_serverType.add(ServerType.valueOf(stString));
		}

		logger.info("KairosDB server type set to: " + m_serverType.toString());
	}


	@Inject
	private SimpleStatsReporter m_simpleStatsReporter = new SimpleStatsReporter();

	@Inject
	public MetricsResource(KairosDatastore datastore, QueryParser queryParser,
			KairosDataPointFactory dataPointFactory, FilterEventBus eventBus)
	{
		this.datastore = requireNonNull(datastore);
		this.queryParser = requireNonNull(queryParser);
		m_kairosDataPointFactory = dataPointFactory;
		m_publisher = requireNonNull(eventBus).createPublisher(DataPointEvent.class);
		formatters.put("json", new JsonFormatter());

		GsonBuilder builder = new GsonBuilder();
		gson = builder.disableHtmlEscaping().create();
	}

	public static ResponseBuilder setHeaders(ResponseBuilder responseBuilder)
	{
		responseBuilder.header("Access-Control-Allow-Origin", "*");
		responseBuilder.header("Pragma", "no-cache");
		responseBuilder.header("Cache-Control", "no-cache");
		responseBuilder.header("Expires", 0);

		return (responseBuilder);
	}

	private void checkServerType(ServerType methodServerType, String methodName, String requestType) throws InvalidServerTypeException
	{
		checkServerTypeStatic(m_serverType, methodServerType, methodName, requestType);
	}

	@VisibleForTesting
	static void checkServerTypeStatic(EnumSet<ServerType> serverType, ServerType methodServerType, String methodName, String requestType) throws InvalidServerTypeException
	{
		logger.debug("checkServerType() - KairosDB ServerType set to " + serverType.toString());
		if (!serverType.contains(methodServerType))
		{
			String logtext = "Disabled request type: " + methodServerType.name() + ", " + requestType + " request via URI \"" +  methodName + "\"";
			logger.info(logtext);
			String exceptionMessage = "{\"errors\": [\"Forbidden: " + methodServerType.toString() + " API methods are disabled on this KairosDB instance.\"]}";
			throw new InvalidServerTypeException(exceptionMessage);
		}
	}

	@OPTIONS
	@Produces(MediaType.APPLICATION_JSON + "; charset=UTF-8")
	@Path("/version")
	public Response corsPreflightVersion(@HeaderParam("Access-Control-Request-Headers") final String requestHeaders,
			@HeaderParam("Access-Control-Request-Method") final String requestMethod)
	{
		ResponseBuilder responseBuilder = getCorsPreflightResponseBuilder(requestHeaders, requestMethod);
		return (responseBuilder.build());
	}

	@GET
	@Produces(MediaType.APPLICATION_JSON + "; charset=UTF-8")
	@Path("/version")
	public Response getVersion()
	{
		Package thisPackage = getClass().getPackage();
		String versionString = thisPackage.getImplementationTitle() + " " + thisPackage.getImplementationVersion();
		ResponseBuilder responseBuilder = Response.status(Response.Status.OK).entity("{\"version\": \"" + versionString + "\"}\n");
		setHeaders(responseBuilder);
		return responseBuilder.build();
	}

	@OPTIONS
	@Produces(MediaType.APPLICATION_JSON + "; charset=UTF-8")
	@Path("/metricnames")
	public Response corsPreflightMetricNames(@HeaderParam("Access-Control-Request-Headers") final String requestHeaders,
			@HeaderParam("Access-Control-Request-Method") final String requestMethod) throws InvalidServerTypeException
	{
		checkServerType(ServerType.QUERY, "/metricnames", "OPTIONS");
		ResponseBuilder responseBuilder = getCorsPreflightResponseBuilder(requestHeaders, requestMethod);
		return (responseBuilder.build());
	}

	@GET
	@Produces(MediaType.APPLICATION_JSON + "; charset=UTF-8")
	@Path("/metricnames")
	public Response getMetricNames(@QueryParam("prefix") String prefix) throws InvalidServerTypeException
	{
		checkServerType(ServerType.QUERY, "/metricnames", "GET");
		
		return executeNameQuery(NameType.METRIC_NAMES, prefix);
	}

	@OPTIONS
	@Produces(MediaType.APPLICATION_JSON + "; charset=UTF-8")
	@Path("/datapoints")
	public Response corsPreflightDataPoints(@HeaderParam("Access-Control-Request-Headers") String requestHeaders,
			@HeaderParam("Access-Control-Request-Method") String requestMethod) throws InvalidServerTypeException
	{
		checkServerType(ServerType.INGEST, "/datapoints", "OPTIONS");
		ResponseBuilder responseBuilder = getCorsPreflightResponseBuilder(requestHeaders, requestMethod);
		return (responseBuilder.build());
	}

	/**
	 * @deprecated  As of release 1.3.0. Use /datapoints with "content-encoding: gzip".
	 */
	@POST
	@Produces(MediaType.APPLICATION_JSON + "; charset=UTF-8")
	@Consumes("application/gzip")
	@Path("/datapoints")
	@Deprecated public Response addGzip(InputStream gzip) throws InvalidServerTypeException
	{
		checkServerType(ServerType.INGEST, "gzip /datapoints", "POST");
		GZIPInputStream gzipInputStream;
		try
		{
			gzipInputStream = new GZIPInputStream(gzip);
		}
		catch (IOException e)
		{
			JsonResponseBuilder builder = new JsonResponseBuilder(Response.Status.BAD_REQUEST);
			return builder.addError(e.getMessage()).build();
		}
		return (add(null, gzipInputStream));
	}

	@POST
	@Produces(MediaType.APPLICATION_JSON + "; charset=UTF-8")
	@Path("/datapoints")
	public Response add(@Context HttpHeaders httpheaders, InputStream stream) throws InvalidServerTypeException
	{
		checkServerType(ServerType.INGEST, "JSON /datapoints", "POST");
		try
		{
			if (httpheaders != null)
			{
				List<String> requestHeader = httpheaders.getRequestHeader("Content-Encoding");
				if (requestHeader != null && requestHeader.contains("gzip"))
				{
					stream = new GZIPInputStream(stream);
				}
			}

			DataPointsParser parser = new DataPointsParser(m_publisher, new InputStreamReader(stream, UTF_8),
					gson, m_kairosDataPointFactory);
			ValidationErrors validationErrors = parser.parse();

			m_ingestedDataPoints.addAndGet(parser.getDataPointCount());
			m_ingestTime.addAndGet(parser.getIngestTime());

			if (!validationErrors.hasErrors())
				return setHeaders(Response.status(Response.Status.NO_CONTENT)).build();
			else
			{
				JsonResponseBuilder builder = new JsonResponseBuilder(Response.Status.BAD_REQUEST);
				for (String errorMessage : validationErrors.getErrors())
				{
					builder.addError(errorMessage);
				}
				return builder.build();
			}
		}
		catch (JsonIOException | MalformedJsonException | JsonSyntaxException e)
		{
			JsonResponseBuilder builder = new JsonResponseBuilder(Response.Status.BAD_REQUEST);
			return builder.addError(e.getMessage()).build();
		}
		catch (Exception e)
		{
			logger.error("Failed to add metric.", e);
			return setHeaders(Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(new ErrorResponse(e.getMessage()))).build();

		}
		catch (OutOfMemoryError e)
		{
			logger.error("Out of memory error.", e);
			return setHeaders(Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(new ErrorResponse(e.getMessage()))).build();
		}
	}

	@GET
	@Produces(MediaType.APPLICATION_JSON + "; charset=UTF-8")
	@Path("/datapoints/index")
	public Response index(@QueryParam("start_absolute") Long startTime, @QueryParam("end_absolute") Long endTime,
			@QueryParam("metric") String metric) throws InvalidServerTypeException
	{
		checkServerType(ServerType.INGEST, "JSON /datapoints/index", "GET");

		try {
			datastore.indexTags(new QueryMetric(startTime, endTime, 0, metric));
			return setHeaders(Response.status(Response.Status.NO_CONTENT)).build();
		}
		catch (DatastoreException e) {
			return setHeaders(Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(new ErrorResponse(e.getMessage()))).build();
		}
		catch (OutOfMemoryError e)
		{
			logger.error("Out of memory error.", e);
			return setHeaders(Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(new ErrorResponse(e.getMessage()))).build();
		}
	}

	@OPTIONS
	@Produces(MediaType.APPLICATION_JSON + "; charset=UTF-8")
	@Path("/datapoints/query/tags")
	public Response corsPreflightQueryTags(@HeaderParam("Access-Control-Request-Headers") final String requestHeaders,
			@HeaderParam("Access-Control-Request-Method") final String requestMethod) throws InvalidServerTypeException
	{
		checkServerType(ServerType.QUERY, "/datapoints/query/tags", "OPTIONS");
		ResponseBuilder responseBuilder = getCorsPreflightResponseBuilder(requestHeaders, requestMethod);
		return (responseBuilder.build());
	}

	@POST
	@Produces(MediaType.APPLICATION_JSON + "; charset=UTF-8")
	@Path("/datapoints/query/tags")
	public Response getMeta(String json) throws InvalidServerTypeException
	{
		checkServerType(ServerType.QUERY, "/datapoints/query/tags", "POST");
		requireNonNull(json);
		logger.debug(json);

		try
		{
			File respFile = File.createTempFile("kairos", ".json", new File(datastore.getCacheDir()));
			BufferedWriter writer = new BufferedWriter(new FileWriter(respFile));

			JsonResponse jsonResponse = new JsonResponse(writer);

			jsonResponse.begin(null);

			List<QueryMetric> queries = queryParser.parseQueryMetric(json).getQueryMetrics();

			for (QueryMetric query : queries)
			{
				List<DataPointGroup> result = datastore.queryTags(query);

				try
				{
					jsonResponse.formatQuery(result, false, -1, false);
				}
				finally
				{
					for (DataPointGroup dataPointGroup : result)
					{
						dataPointGroup.close();
					}
				}
			}

			jsonResponse.end();
			writer.flush();
			writer.close();

			ResponseBuilder responseBuilder = Response.status(Response.Status.OK).entity(
					new FileStreamingOutput(respFile));

			setHeaders(responseBuilder);
			return responseBuilder.build();
		}
		catch (JsonSyntaxException | QueryException e)
		{
			JsonResponseBuilder builder = new JsonResponseBuilder(Response.Status.BAD_REQUEST);
			return builder.addError(e.getMessage()).build();
		}
		catch (BeanValidationException e)
		{
			JsonResponseBuilder builder = new JsonResponseBuilder(Response.Status.BAD_REQUEST);
			return builder.addErrors(e.getErrorMessages()).build();
		}
		catch (MemoryMonitorException e)
		{
			logger.error("Query failed.", e);
			System.gc();
			return setHeaders(Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(new ErrorResponse(e.getMessage()))).build();
		}
		catch (Exception e)
		{
			logger.error("Query failed.", e);
			return setHeaders(Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(new ErrorResponse(e.getMessage()))).build();

		}
		catch (OutOfMemoryError e)
		{
			logger.error("Out of memory error.", e);
			return setHeaders(Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(new ErrorResponse(e.getMessage()))).build();
		}
	}

	/**
	 Information for this endpoint was taken from https://developer.mozilla.org/en-US/docs/HTTP/Access_control_CORS.
	 <p>
	 <p>Response to a cors preflight request to access data.
	 */
	@OPTIONS
	@Produces(MediaType.APPLICATION_JSON + "; charset=UTF-8")
	@Path(QUERY_URL)
	public Response corsPreflightQuery(@HeaderParam("Access-Control-Request-Headers") final String requestHeaders,
			@HeaderParam("Access-Control-Request-Method") final String requestMethod) throws InvalidServerTypeException
	{
		checkServerType(ServerType.QUERY, QUERY_URL, "OPTIONS");
		ResponseBuilder responseBuilder = getCorsPreflightResponseBuilder(requestHeaders, requestMethod);
		return (responseBuilder.build());
	}

	@GET
	@Produces(MediaType.APPLICATION_JSON + "; charset=UTF-8")
	@Path(QUERY_URL)
	public Response getQuery(@QueryParam("query") String json, @Context HttpServletRequest request) throws Exception
	{
		checkServerType(ServerType.QUERY, QUERY_URL, "GET");
		return runQuery(json, request.getRemoteAddr());
	}

	@POST
	@Produces(MediaType.APPLICATION_JSON + "; charset=UTF-8")
	@Path(QUERY_URL)
	public Response postQuery(String json, @Context HttpServletRequest request) throws Exception
	{
		checkServerType(ServerType.QUERY, QUERY_URL, "POST");
		return runQuery(json, request.getRemoteAddr());
	}


	public Response runQuery(String json, String remoteAddr) throws Exception
	{
		logger.debug(json);
		boolean queryFailed = false;

		ThreadReporter.setReportTime(System.currentTimeMillis());
		ThreadReporter.addTag("host", hostName);

		try
		{
			if (json == null)
				throw new BeanValidationException(new QueryParser.SimpleConstraintViolation("query json", "must not be null or empty"), "");

			File respFile = File.createTempFile("kairos", ".json", new File(datastore.getCacheDir()));
			BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(respFile), UTF_8));

			JsonResponse jsonResponse = new JsonResponse(writer);

			String originalQuery = null;
			if (m_returnQueryInResponse)
				originalQuery = json;
			jsonResponse.begin(originalQuery);

			Query mainQuery = queryParser.parseQueryMetric(json);
			mainQuery = m_queryPreProcessor.preProcess(mainQuery);

			List<QueryMetric> queries = mainQuery.getQueryMetrics();

			int queryCount = 0;
			for (QueryMetric query : queries)
			{
				queryCount++;
				ThreadReporter.addTag("metric_name", query.getName());
				ThreadReporter.addTag("query_index", String.valueOf(queryCount));

				DatastoreQuery dq = datastore.createQuery(query);
				long startQuery = System.currentTimeMillis();

				try
				{
					List<DataPointGroup> results = dq.execute();
					jsonResponse.formatQuery(results, query.isExcludeTags(), dq.getSampleSize(), true);

					ThreadReporter.addDataPoint(QUERY_TIME, System.currentTimeMillis() - startQuery);
				}
				finally
				{
					dq.close();
				}
			}

			jsonResponse.end();
			writer.flush();
			writer.close();


			//System.out.println("About to process plugins");
			List<QueryPlugin> plugins = mainQuery.getPlugins();
			for (QueryPlugin plugin : plugins)
			{
				if (plugin instanceof QueryPostProcessingPlugin)
				{
					respFile = ((QueryPostProcessingPlugin) plugin).processQueryResults(respFile);
				}
			}

			ResponseBuilder responseBuilder = Response.status(Response.Status.OK).entity(
					new FileStreamingOutput(respFile));

			setHeaders(responseBuilder);
			return responseBuilder.build();
		}
		catch (JsonSyntaxException | QueryException e)
		{
			queryFailed = true;
			JsonResponseBuilder builder = new JsonResponseBuilder(Response.Status.BAD_REQUEST);
			return builder.addError(e.getMessage()).build();
		}
		catch (BeanValidationException e)
		{
			queryFailed = true;
			JsonResponseBuilder builder = new JsonResponseBuilder(Response.Status.BAD_REQUEST);
			return builder.addErrors(e.getErrorMessages()).build();
		}
		catch (MemoryMonitorException e)
		{
			queryFailed = true;
			logger.error("Query failed.", e);
			Thread.sleep(1000);
			System.gc();
			return setHeaders(Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(new ErrorResponse(e.getMessage()))).build();
		}
		catch (IOException e)
		{
			queryFailed = true;
			logger.error("Failed to open temp folder " + datastore.getCacheDir(), e);
			return setHeaders(Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(new ErrorResponse(e.getMessage()))).build();
		}
		catch (Exception e)
		{
			queryFailed = true;
			logger.error("Query failed.", e);
			return setHeaders(Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(new ErrorResponse(e.getMessage()))).build();
		}
		catch (OutOfMemoryError e)
		{
			queryFailed = true;
			logger.error("Out of memory error.", e);
			return setHeaders(Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(new ErrorResponse(e.getMessage()))).build();

		}
		finally
		{
			ThreadReporter.clearTags();
			ThreadReporter.addTag("host", hostName);

			if (queryFailed)
				ThreadReporter.addTag("status", "failed");
			else
				ThreadReporter.addTag("status", "success");

			//write metrics for query logging
			long queryTime = System.currentTimeMillis() - ThreadReporter.getReportTime();
			if (m_logQueries && ((queryTime / 1000) >= m_logQueriesLongerThan))
			{
				ThreadReporter.addDataPoint("kairosdb.log.query.remote_address", remoteAddr, m_logQueriesTtl);
				ThreadReporter.addDataPoint("kairosdb.log.query.json", json, m_logQueriesTtl);
			}

			ThreadReporter.addTag("request", QUERY_URL);
			ThreadReporter.addDataPoint(REQUEST_TIME, queryTime);


			if (m_aggregatedQueryMetrics)
			{
				ThreadReporter.gatherData(m_statsMap);
			}
			else
			{
				ThreadReporter.submitData(m_longDataPointFactory,
						m_stringDataPointFactory, m_publisher);
			}

			ThreadReporter.clear();
		}
	}

	@OPTIONS
	@Produces(MediaType.APPLICATION_JSON + "; charset=UTF-8")
	@Path("/datapoints/delete")
	public Response corsPreflightDelete(@HeaderParam("Access-Control-Request-Headers") final String requestHeaders,
			@HeaderParam("Access-Control-Request-Method") final String requestMethod) throws InvalidServerTypeException
	{
		checkServerType(ServerType.DELETE, "/datapoints/delete", "OPTIONS");
		ResponseBuilder responseBuilder = getCorsPreflightResponseBuilder(requestHeaders, requestMethod);
		return (responseBuilder.build());
	}

	@POST
	@Produces(MediaType.APPLICATION_JSON + "; charset=UTF-8")
	@Path("/datapoints/delete")
	public Response delete(String json) throws Exception
	{
		checkServerType(ServerType.DELETE, "/datapoints/delete", "POST");
		requireNonNull(json);
		logger.debug(json);

		try
		{
			List<QueryMetric> queries = queryParser.parseQueryMetric(json).getQueryMetrics();

			for (QueryMetric query : queries)
			{
				datastore.delete(query);
			}

			return setHeaders(Response.status(Response.Status.NO_CONTENT)).build();
		}
		catch (JsonSyntaxException | QueryException e)
		{
			JsonResponseBuilder builder = new JsonResponseBuilder(Response.Status.BAD_REQUEST);
			return builder.addError(e.getMessage()).build();
		}
		catch (BeanValidationException e)
		{
			JsonResponseBuilder builder = new JsonResponseBuilder(Response.Status.BAD_REQUEST);
			return builder.addErrors(e.getErrorMessages()).build();
		}
		catch (MemoryMonitorException e)
		{
			logger.error("Query failed.", e);
			System.gc();
			return setHeaders(Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(new ErrorResponse(e.getMessage()))).build();
		}
		catch (Exception e)
		{
			logger.error("Delete failed.", e);
			return setHeaders(Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(new ErrorResponse(e.getMessage()))).build();

		}
		catch (OutOfMemoryError e)
		{
			logger.error("Out of memory error.", e);
			return setHeaders(Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(new ErrorResponse(e.getMessage()))).build();
		}
	}

	public static ResponseBuilder getCorsPreflightResponseBuilder(final String requestHeaders,
			final String requestMethod)
	{
		ResponseBuilder responseBuilder = Response.status(Response.Status.OK);
		responseBuilder.header("Access-Control-Allow-Origin", "*");
		responseBuilder.header("Access-Control-Allow-Headers", requestHeaders);
		responseBuilder.header("Access-Control-Max-Age", "86400"); // Cache for one day
		if (requestMethod != null)
		{
			responseBuilder.header("Access-Control-Allow_Method", requestMethod);
		}

		return responseBuilder;
	}


	@OPTIONS
	@Produces(MediaType.APPLICATION_JSON + "; charset=UTF-8")
	@Path("/metric/{metricName}")
	public Response corsPreflightMetricDelete(@HeaderParam("Access-Control-Request-Headers") String requestHeaders,
			@HeaderParam("Access-Control-Request-Method") String requestMethod) throws InvalidServerTypeException
	{
		checkServerType(ServerType.DELETE, "/metric/{metricName}", "OPTIONS");
		ResponseBuilder responseBuilder = getCorsPreflightResponseBuilder(requestHeaders, requestMethod);
		return (responseBuilder.build());
	}

	@DELETE
	@Produces(MediaType.APPLICATION_JSON + "; charset=UTF-8")
	@Path("/metric/{metricName}")
	public Response metricDelete(@PathParam("metricName") String metricName) throws Exception
	{
		checkServerType(ServerType.DELETE, "/metric/{metricName}", "DELETE");
		try
		{
			QueryMetric query = new QueryMetric(datastore.getDatastore().getMinTimeValue(), datastore.getDatastore().getMaxTimeValue(), 0, metricName);
			datastore.delete(query);


			return setHeaders(Response.status(Response.Status.NO_CONTENT)).build();
		}
		catch (Exception e)
		{
			logger.error("Delete failed.", e);
			return setHeaders(Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(new ErrorResponse(e.getMessage()))).build();
		}
	}

	private Response executeNameQuery(NameType type)
	{
		return executeNameQuery(type, null);
	}

	private Response executeNameQuery(NameType type, String prefix)
	{
		try
		{
			Iterable<String> values = null;
			switch (type)
			{
				case METRIC_NAMES:
					values = datastore.getMetricNames(prefix);
					break;
				case TAG_KEYS:
					values = datastore.getTagNames();
					break;
				case TAG_VALUES:
					values = datastore.getTagValues();
					break;
			}

			DataFormatter formatter = formatters.get("json");

			ResponseBuilder responseBuilder = Response.status(Response.Status.OK).entity(
					new ValuesStreamingOutput(formatter, values));
			setHeaders(responseBuilder);
			return responseBuilder.build();
		}
		catch (Exception e)
		{
			logger.error("Failed to get " + type, e);
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(
					new ErrorResponse(e.getMessage())).build();
		}
	}

	@Override
	public List<DataPointSet> getMetrics(long now)
	{
		int time = m_ingestTime.getAndSet(0);
		int count = m_ingestedDataPoints.getAndSet(0);
		List<DataPointSet> ret = new ArrayList<>();

		if (count != 0)
		{

			DataPointSet dpsCount = new DataPointSet(INGEST_COUNT);
			DataPointSet dpsTime = new DataPointSet(INGEST_TIME);

			dpsCount.addTag("host", hostName);
			dpsTime.addTag("host", hostName);

			dpsCount.addDataPoint(m_longDataPointFactory.createDataPoint(now, count));
			dpsTime.addDataPoint(m_longDataPointFactory.createDataPoint(now, time));

			ret.add(dpsCount);
			ret.add(dpsTime);
		}

		Map<String, SimpleStats> statsMap = m_statsMap.getStatsMap();

		for (Map.Entry<String, SimpleStats> entry : statsMap.entrySet())
		{
			String metric = entry.getKey();
			SimpleStats.Data stats = entry.getValue().getAndClear();

			m_simpleStatsReporter.reportStats(stats, now, metric, ret);
		}

		return ret;
	}

	public static class ValuesStreamingOutput implements StreamingOutput
	{
		private DataFormatter m_formatter;
		private Iterable<String> m_values;

		public ValuesStreamingOutput(DataFormatter formatter, Iterable<String> values)
		{
			m_formatter = formatter;
			m_values = values;
		}

		@SuppressWarnings("ResultOfMethodCallIgnored")
		public void write(OutputStream output) throws IOException, WebApplicationException
		{
			Writer writer = new OutputStreamWriter(output, UTF_8);

			try
			{
				m_formatter.format(writer, m_values);
			}
			catch (FormatterException e)
			{
				logger.error("Description of what failed:", e);
			}

			writer.flush();
		}
	}

	public static class FileStreamingOutput implements StreamingOutput
	{
		private File m_responseFile;

		public FileStreamingOutput(File responseFile)
		{
			m_responseFile = responseFile;
		}

		@SuppressWarnings("ResultOfMethodCallIgnored")
		@Override
		public void write(OutputStream output) throws IOException, WebApplicationException
		{
			try (InputStream reader = new FileInputStream(m_responseFile))
			{
				byte[] buffer = new byte[1024];
				int size;

				while ((size = reader.read(buffer)) != -1)
				{
					output.write(buffer, 0, size);
				}

				output.flush();
			}
			finally
			{
				m_responseFile.delete();
			}
		}
	}
}
