/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * https://github.com/CILEA/dspace-cris/wiki/License
 */
package org.dspace.app.webui.cris.servlet;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.dspace.app.cris.model.CrisConstants;
import org.dspace.app.cris.util.Researcher;
import org.dspace.app.webui.cris.rest.dris.JsonLdEntry;
import org.dspace.app.webui.cris.rest.dris.JsonLdOrganizationalUnit;
import org.dspace.app.webui.cris.rest.dris.JsonLdResult;
import org.dspace.app.webui.cris.rest.dris.JsonLdResultsList;
import org.dspace.app.webui.cris.rest.dris.JsonLdVocabs;
import org.dspace.app.webui.servlet.DSpaceServlet;
import org.dspace.authorize.AuthorizeException;
import org.dspace.core.Context;
import org.dspace.core.LogManager;
import org.dspace.discovery.SearchService;
import org.dspace.discovery.SearchServiceException;
import org.dspace.services.ConfigurationService;

import com.fasterxml.jackson.databind.ObjectMapper;

import ioinformarics.oss.jackson.module.jsonld.JsonldModule;

/**
 * Servlet that implements the DRIS API querying services. Three main kind of query
 * are supported calling the servlet with these keywords after the basic URL:
 * <ul>
 * <li><code>/entries</code>: find DRIS entries</li>
 * <li><code>/orgunits</code>: find DRIS organizational units</li>
 * <li><code>/vocabs</code>: find DRIS vocabs</li>
 * </ul>
 * Any kind of sub-query can have some parameters as path elements and/or regular http parameters.
 * See documents "DRIS API specification_20200625.pdf" and
 * "Recommendations for the DRIS (1c8cfdbc-e5be-40d8-9cc7-e11d3dcc2042).pdf" for more details.
 * 
 * @author Mastratisi  
 */
@SuppressWarnings("serial")
public class DrisQueryingServlet extends DSpaceServlet {

    // Log4J object for file logging purposes
    private static Logger log = Logger.getLogger(DrisQueryingServlet.class);
    
	// The descriptive name of this servlet, for logging purposes
	private static String SERVLET_DESCRIPTION = "Dris Querying API Servlet";
	// General (production) URL of the API, now setted by dsGet for any call
	private static String API_URL = "https://api.eurocris.org";
	// Types of query the servlet supports
	public final static String ENTRIES_QUERY_TYPE_NAME = "entries";
	public final static String ORG_UNITS_QUERY_TYPE_NAME = "orgunits";
	public final static String VOCABS_QUERY_TYPE_NAME = "vocabs";
	// Sub-type of "vocabs" query type the servlet supports
	public final static String VOCABS_QUERY_TYPE_STATUSES_SUB_TYPE = "statuses";
	public final static String VOCABS_QUERY_TYPE_SCOPES_SUB_TYPE = "scopes";
	public final static String VOCABS_QUERY_TYPE_COVERAGES_SUB_TYPE = "coverages";
	public final static String VOCABS_QUERY_TYPE_CRIS_PLATFORMS_SUB_TYPE = "cris-platforms";
	public final static String VOCABS_QUERY_TYPE_COUNTRIES_SUB_TYPE = "countries";

	// Properties specifying prefix for querying filters criteria
	private String filtersSettingsPrefix = "dris.querying.filter.";
	// Services and utility object to perform database and Solr queries
	private SearchService searchService;
	private ConfigurationService configurationService;
    // Special character and keyword
	private static final String parametersSeparator = "&";
	private static final String keyValueSeparator = "=";
	private static final String multiValueSeparator = ",";
	private static final String maxPageSizeParameterName = "max-page-size";
	private static final String pageStartDocNumberParameterName = "page-start-doc";
	private final static Integer DEFAULT_MAX_PAGE_SIZE = 100;
	private final static Integer DEFAULT_START_DOC_NUMBER = 0;
	private static final String responsePaginationHeaderName = "Link";
	private static final String responsePaginationHeaderPrefix = "<";
	private static final String responsePaginationHeaderPostfix = ">; rel=next";
	private static final String responseLastModifiedHeaderName = "Last-Modified";


    /**
     * Servlet initialization method, it creates the services objects needed to query the database and the Solr repository.
     * 
	 * @param request the <code>javax.servlet.ServletConfig</code> passed by the application container
     */
	@Override
    public void init(ServletConfig config) throws ServletException {
		try {
			log.info(SERVLET_DESCRIPTION + ": initialization started...");
			super.init(config);
			Researcher util = new Researcher();
			this.searchService = util.getCrisSearchService();
			this.configurationService = util.getConfigurationService(); 
			// CALLED for any 'dsGet' -- this.loadFiltersPropertiesFromFile();
			log.info(SERVLET_DESCRIPTION + ": successfully initialized.");
		} catch (Exception e) {
			// ERROR: initialization failed
			String errMsg = SERVLET_DESCRIPTION + ": critical error during servlet initialization.";
			log.error(errMsg, e);
			throw new ServletException(errMsg, e);
		}
    }

	/**
	 * Ensures that POST calls will be treated as GET calls.
	 */
	@Override
	protected void doDSPost(Context context, HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException, SQLException, AuthorizeException {
		log.info(LogManager.getHeader(context, SERVLET_DESCRIPTION, "POST calling, it will be treated as GET..."));
		this.doDSGet(context, request, response);
	}
	
	/**
	 * Utility for split the 'path parameters' of the specified request as a String array.
	 * We define path parameters as the path elements after the basic servlet URL (and before
	 * the regular http parameters), as returned by <code>javax.servlet.http.HttpServletRequest.getPathInfo()</code>.
	 * At least one path parameter is expected.
	 * 
	 * @param request the <code>javax.servlet.http.HttpServletRequest</code> to analyze
	 * @return the 'path parameters' of the supplied request splitted as separate element of a String array
	 * @throws ServletException if no path parameters was found or other errors occurred during request analysis
	 */
	private String[] splitMandatoryPathParameters(HttpServletRequest request) throws ServletException {
		String pathInfo = StringUtils.trimToEmpty(request.getPathInfo());
		if (StringUtils.isEmpty(pathInfo)) {
			String errMsg = "No path parameters (i.e. query type like 'entries', 'vocabs', etc.) was found.";
			log.error(SERVLET_DESCRIPTION + ":"+  errMsg);
			throw new ServletException(errMsg);
		}
		try {
			pathInfo = URLDecoder.decode(pathInfo, StandardCharsets.UTF_8.toString());
		} catch (UnsupportedEncodingException e) {
			String errMsg = "Unable to decode path parameters.";
			log.error(SERVLET_DESCRIPTION + ":"+  errMsg, e);
			throw new ServletException(errMsg, e);
		}
		if (!pathInfo.startsWith("/")) {
			String errMsg = "Unable to process path parameters, no initial '/' was found.";
			log.error(SERVLET_DESCRIPTION + ":"+  errMsg);
			throw new ServletException(errMsg);
		}
		return pathInfo.substring(1).split("/");
	}
	
	
	/**
	 * Utility to split the 'http parameters' of the specified request as a key --> multi-value map of Strings.
	 * Http parameters are specified after '?' and separated by '&' in the String
	 * returned by <code>javax.servlet.http.HttpServletRequest.getQueryString()</code>.
	 * The parameters set can also be empty (no errors will be raised in this case).
	 * If not empty the parameters set will be returned in the same order as it appears in the request.
	 * Multiple values of the same filter parameter are allowed, specified either by multiple occurrences of the parameter or
	 * as a comma-separated list of values of the parameter.
	 * 
	 * @param request the <code>javax.servlet.http.HttpServletRequest</code> to analyze
	 * @return the 'http parameters' of the supplied request as an ordered key --> multi-value map
	 * @throws ServletException if errors occurred during request analysis
	 */
	private LinkedHashMap<String, List<String>> splitOptionalHttpParameters(HttpServletRequest request) throws ServletException {
		LinkedHashMap<String, List<String>> paramsMap = new LinkedHashMap<>();
		String httpParameters = StringUtils.trimToEmpty(request.getQueryString());
		if (!StringUtils.isEmpty(httpParameters)) {
			try {
				httpParameters = URLDecoder.decode(httpParameters, StandardCharsets.UTF_8.toString());
			} catch (UnsupportedEncodingException e) {
				String errMsg = "Unable to decode http parameters.";
				log.error(SERVLET_DESCRIPTION + ":"+  errMsg, e);
				throw new ServletException(errMsg, e);
			}			
			String[] primaryKeysValuesCouples = httpParameters.split(parametersSeparator);
			for (String p: primaryKeysValuesCouples) {
				String[] primaryKV = p.split(keyValueSeparator);
				String primaryK = primaryKV[0];
				String primaryV = primaryKV[1];
				List<String> candidateVs = new LinkedList<>(Arrays.asList(primaryV.split(multiValueSeparator)));
				if (paramsMap.containsKey(primaryK)) {
					paramsMap.get(primaryK).addAll(candidateVs);
				} else {
					paramsMap.put(primaryK, candidateVs);
				}
			}
		}
		return paramsMap;
	}

	/**
	 * Utility for remove the first element in a String array.
	 * 
	 * @param originalArray the given String array
	 * @return a new String array without the first element or an empty array if <code>originalArray</code>
	 * 		   does not have enough elements (i.e. <code>originalArray.length <= 1</code>)
	 */
	private String[] removeFirstItem(String[] originalArray) {
		String[] retValue = {};
		if (originalArray.length > 1) {
			retValue = new String[originalArray.length - 1];
			for (int i = 1; i < originalArray.length; i++) {
				retValue[i - 1] = originalArray[i];
			}
		}
		return retValue;
	}
	
	/**
	 * Utility that gets the full URL of a given request.
	 * 
	 * @param request the <code>javax.servlet.http.HttpServletRequest</code> to analyze
	 * @return the full URL called with the supplied request
	 */
	private String getFullURL(HttpServletRequest request) {
	    StringBuilder requestURL = new StringBuilder(StringUtils.trimToEmpty(request.getRequestURL().toString()));
	    String queryString = StringUtils.trimToEmpty(request.getQueryString());
	    if (StringUtils.isEmpty(queryString)) {
	        return requestURL.toString();
	    } else {
	        return (requestURL.append('?').append(queryString)).toString();
	    }
	}
	
	/**
	 * Utility that gets the base URL (without path and http parameters) of a given request.
	 * 
	 * @param request the <code>javax.servlet.http.HttpServletRequest</code> to analyze
	 * @return the base URL called with the supplied request
	 */
	private String getBaseURL(HttpServletRequest request) {
	    StringBuilder requestURL = new StringBuilder(StringUtils.trimToEmpty(request.getRequestURL().toString()));
	    String pathInfo = StringUtils.trimToEmpty(request.getPathInfo());
	    StringBuilder baseURLrequestURL = requestURL.delete(requestURL.indexOf(pathInfo), requestURL.length());
	    return baseURLrequestURL.toString();
	}
	
	/**
	 * Main servlet entry point for querying. The method analyzes the supplied parameters, as path elements (after the main
	 * servlet mapped url) and/or real http parameters and launches the appropriate methods based on the type of processing required
	 * or throws an error in case of not supported call.
	 * Moreover, the method returns the response to the caller by serializing the JSON-LD result returned by the specific methods.
	 */
	@Override
	protected void doDSGet(Context context, HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException, SQLException {
		String calledFullUrl = this.getFullURL(request);
		DrisQueryingServlet.setMainApiUrl(this.getBaseURL(request));
		log.info(SERVLET_DESCRIPTION + ": GET calling started...");
		log.debug(SERVLET_DESCRIPTION + ": Called URL: " + calledFullUrl);
		List<? extends JsonLdResult> jsonLdResults = new LinkedList<>();
		response.setContentType("application/json; charset=UTF-8");
		// Extract the path after "/jspui/dris" (here called the "path parameters")
		// and the http parameters (after '?', separated by '&') provided by the caller.
		// The path parameters set can not be empty, the http parameters set can be empty.
		String[] pathElements = this.splitMandatoryPathParameters(request);
		LinkedHashMap<String, List<String>> paramsMap = this.splitOptionalHttpParameters(request);
		// Check and extract pagination parameters
		Integer maxPageSize = DEFAULT_MAX_PAGE_SIZE;
		Integer startPageDocNumb = DEFAULT_START_DOC_NUMBER;
		if (paramsMap.containsKey(maxPageSizeParameterName) && !paramsMap.get(maxPageSizeParameterName).isEmpty()) {
			try {
				maxPageSize = Integer.parseInt(paramsMap.get(maxPageSizeParameterName).get(0));
				if (maxPageSize < 1) throw new NumberFormatException("The value of " + maxPageSizeParameterName + " must be greater than 0.");
				
				if (paramsMap.containsKey(pageStartDocNumberParameterName) && !paramsMap.get(pageStartDocNumberParameterName).isEmpty()) {
					startPageDocNumb = Integer.parseInt(paramsMap.get(pageStartDocNumberParameterName).get(0));
					if (startPageDocNumb < 1) throw new NumberFormatException("The value of " + pageStartDocNumberParameterName + " must be greater than 0.");
				}
			} catch (NumberFormatException e) {
				log.warn(SERVLET_DESCRIPTION + ": Error during numeric paramenters parsing, they will be ignored.", e);
				maxPageSize = DEFAULT_MAX_PAGE_SIZE;
				startPageDocNumb = DEFAULT_START_DOC_NUMBER;
			}
		}
		try {
			// Recognize the main query type as the first path element
			// The 'pathElements' contains at least one element
			// because 'this.splitMandatoryPathParameters' did not raised any error
			String queryType = pathElements[0];
			boolean isQuerying = false;
			if (queryType.equals(ENTRIES_QUERY_TYPE_NAME)) {
				if (pathElements.length == 1) {
					if (paramsMap.isEmpty()) {
						// All entries requested, without Id or filtering parameters
						jsonLdResults = this.processAllEntriesQueryType(maxPageSize, startPageDocNumb);
					} else {
						// Entries filtered by various criteria was requested
						jsonLdResults = this.processEntriesQueryTypeWithFilteringParameters(paramsMap, maxPageSize, startPageDocNumb);
					}
					isQuerying = true;
				} else if (pathElements.length == 2) {
					// Single entry by id was requested
					jsonLdResults = this.processEntriesQueryTypeById(pathElements[1], maxPageSize, startPageDocNumb);
				} else {
					// Unrecognized query of type 'entries', throw an error
					log.error(LogManager.getHeader(context, SERVLET_DESCRIPTION, "Unrecognized entries query: " + pathElements.toString()));
					response.sendError(HttpServletResponse.SC_NOT_FOUND);
					return;
				}
			} else if (queryType.equals(ORG_UNITS_QUERY_TYPE_NAME)) {
				// Process org units request (with or without other path parameters)
				jsonLdResults = this.processOrgUnitsQueryType(this.removeFirstItem(pathElements), maxPageSize, startPageDocNumb);
			} else if (queryType.equals(VOCABS_QUERY_TYPE_NAME)) {
				// Process vocabs request (with or without other path parameters)
				jsonLdResults = this.processVocabsQueryType(this.removeFirstItem(pathElements), maxPageSize, startPageDocNumb);
				response.setHeader(responseLastModifiedHeaderName, (new java.util.Date()).toString());
			} else {
				// Unrecognized query type, throw an error
				log.error(LogManager.getHeader(context, SERVLET_DESCRIPTION, "Unrecognized main query type: " + queryType));
				response.sendError(HttpServletResponse.SC_NOT_FOUND);
				return;
			}
			// Compose the link to forward traversal the results list (pagination), set to response header 'Link'
			if (jsonLdResults.size() >= maxPageSize) {
				if (paramsMap.containsKey(pageStartDocNumberParameterName) && !paramsMap.get(pageStartDocNumberParameterName).isEmpty()) {
					calledFullUrl = calledFullUrl.replace(pageStartDocNumberParameterName + keyValueSeparator + startPageDocNumb, 
										  pageStartDocNumberParameterName + keyValueSeparator + (startPageDocNumb + maxPageSize));
				} else {
					calledFullUrl = calledFullUrl + parametersSeparator + pageStartDocNumberParameterName + keyValueSeparator + (startPageDocNumb + maxPageSize);
				}
				response.setHeader(responsePaginationHeaderName, responsePaginationHeaderPrefix + calledFullUrl + responsePaginationHeaderPostfix);
			}
			
            if (isQuerying)
            {
                // Compose data response using ​ https://schema.org/ItemList
                JsonLdResultsList finalResults = new JsonLdResultsList();
                finalResults.setItemListElement(jsonLdResults);
                String jsonLdString = "";
                try
                {
                    ObjectMapper objectMapper = new ObjectMapper();
                    objectMapper.registerModule(new JsonldModule());
                    jsonLdString = objectMapper
                            .writeValueAsString(finalResults);
                    response.getWriter().print(jsonLdString);
                    log.debug(LogManager.getHeader(context, SERVLET_DESCRIPTION,
                            "JSON-LD response ready....."));
                    log.debug(LogManager.getHeader(context, SERVLET_DESCRIPTION,
                            jsonLdString));
                }
                catch (Exception e)
                {
                    log.error(LogManager.getHeader(context, SERVLET_DESCRIPTION,
                            "Unable to write JSON-LD results in the response as a String"),
                            e);
                    response.sendError(HttpServletResponse.SC_NOT_FOUND);
                    return;
                }
            }
            else {
                // response is a json object
                String jsonLdString = "";
                try
                {
                    if(!jsonLdResults.isEmpty()) {
                        ObjectMapper objectMapper = new ObjectMapper();
                        objectMapper.registerModule(new JsonldModule());
                        if (jsonLdResults.size() > 1)
                        {
                            jsonLdString = objectMapper
                                    .writeValueAsString(jsonLdResults);
                        }
                        else
                        {
                            jsonLdString = objectMapper
                                    .writeValueAsString(jsonLdResults.get(0));
                        }
                        response.getWriter().print(jsonLdString);
                        log.debug(LogManager.getHeader(context, SERVLET_DESCRIPTION,
                                "JSON-LD response ready....."));
                        log.debug(LogManager.getHeader(context, SERVLET_DESCRIPTION,
                                jsonLdString));
                    }
                }
                catch (Exception e)
                {
                    log.error(LogManager.getHeader(context, SERVLET_DESCRIPTION,
                            "Unable to write JSON-LD results in the response as a String"),
                            e);
                    response.sendError(HttpServletResponse.SC_NOT_FOUND);
                    return;
                }
            }
			log.info(LogManager.getHeader(context, SERVLET_DESCRIPTION, "Call to Dris Querying Servlet successfully completed."));
			return;
		} catch (ServletException e) {
			// ERROR: specific query type error
			log.error(LogManager.getHeader(context, SERVLET_DESCRIPTION, e.getMessage()), e);
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
	}
	
	/**
	 * Find and returns a specific DRIS entry by its cris-id.
	 * It will be launched to answer to servlet call in the form <code>entries/entry-id</code>.
	 * Examples of queries this method answer:
	 * <ul>
	 * 	<li><code>entries/dris00896</code></li>
	 * </ul>
	 * 
	 * @param entryId the DRIS entry id
	 * @param maxPageSize the maximum number of results to return 
	 * @param startPageDocNumb the start document index in the result sets to start with
	 * @return the list of <code>org.dspace.app.webui.cris.util.JsonLdEntry</code> resulting from the query
	 * @throws ServletException if any error occurs during processing
	 */
	private List<JsonLdEntry> processEntriesQueryTypeById(String entryId, Integer maxPageSize, Integer startPageDocNumb) throws ServletException {
		log.info(SERVLET_DESCRIPTION +":"+ "Query of type 'entries' by Id started...");
		log.debug(SERVLET_DESCRIPTION +":"+ "Id: " + entryId);
		SolrDocumentList solrResults = new SolrDocumentList();
		SolrQuery solrQuery = new SolrQuery();
		QueryResponse rsp;
		try {
			solrQuery = new SolrQuery();
			solrQuery.setQuery("crisdo.type:dris");
			solrQuery.addFilterQuery("cris-id:\"" + entryId + "\"");
			solrQuery.setRows(maxPageSize);
			solrQuery.setStart(startPageDocNumb);
			rsp = this.getCrisSearchService().search(solrQuery);
			solrResults = rsp.getResults();
			Iterator<SolrDocument> iter = solrResults.iterator();
			try {
				// VALUTARE QUALI INFORMAZIONI (E COME) VANNO EFFETTIVAMENTE RIPORTATI NEL JSON-LD
				ObjectMapper objectMapper = new ObjectMapper();
				objectMapper.registerModule(new JsonldModule());
				List<JsonLdEntry> list = new LinkedList<>();
				while (iter.hasNext()) {
					list.add(JsonLdEntry.buildFromSolrDoc(this.getCrisSearchService(), iter.next()));
				}
				if (list.size() != 1) {
					String errMsg = "Warning: no cris object or too much cris objects was found by the specified ID.";
					log.warn(SERVLET_DESCRIPTION + ":"+  errMsg);
				}
				return list;
			} catch (Exception e) {
				log.error(SERVLET_DESCRIPTION + ":"+  e.getMessage(), e);
				throw new ServletException(e);
			}
		} catch (SearchServiceException e) {
			log.error(SERVLET_DESCRIPTION + ":"+  e.getMessage(), e);
			throw new ServletException(e);
		}
	}
	
	/**
	 * Returns a path parameter given as the last part of an URI.
	 * 
	 * @param maybeAnUri the (eventual) URI that contains the parameter
	 * @return the extracted parameter or the trimmed version of <code>maybeAnUri</code> if it is not an URI
	 */
	private String extractParameterValueFromEventualUri(String maybeAnUri) {
		String retValue = StringUtils.trimToEmpty(maybeAnUri);
		boolean isAnUri = true;
		try {
			URL obj = new URL(StringUtils.trimToEmpty(maybeAnUri));
			obj.toURI();
		} catch (MalformedURLException e) {
			isAnUri = false;
		} catch (URISyntaxException e) {
			isAnUri = false;
		}
		try {
			if (isAnUri) {
				String decodedUri = StringUtils.trimToEmpty(URLDecoder.decode(StringUtils.trimToEmpty(maybeAnUri), StandardCharsets.UTF_8.toString()));
				String[] splittedUri = decodedUri.split("/");
				retValue = splittedUri[splittedUri.length - 1];
			}
		} catch (UnsupportedEncodingException e) {
			log.warn(SERVLET_DESCRIPTION + ": Error during URI decoding, raw value will be used: " + retValue, e);
		}
		return retValue;
	}
	
	/**
	 * Extract a path parameters list from a list of (eventual) URI.
	 * 
	 * @param maybeUris the (eventual) URIs that contain the parameters
	 * @return the extracted parameters
	 * @see <code>extractParameterValueFromEventualUri</code>
	 */
	private List<String> extractParameterValuesFromEventualUris(List<String> maybeUris) {
		List<String> retVs = new LinkedList<>();
		for (String mUr: maybeUris) {retVs.add(this.extractParameterValueFromEventualUri(mUr));}
		return retVs;
	}
	
	/**
	 * Returns a Solr filter condition where the <code>field</code> is compared (equality) to the different values
	 * in <code>values</code> and any comparison is in OR with the others.
	 * 
	 * @param field the Solr field to compare
	 * @param values the list of values
	 * @return the Solr OR condition
	 */
	private String composeORFilteringCondition(String field, List<String> values) {
		StringBuilder sb = new StringBuilder();
		boolean first = true;
		for (String v: values) {
			if (!first) sb.append(" OR ");
			else first = false;
			sb.append(field + ":\"" + v + "\"");
		}
		return sb.toString();
	}

	/**
	 * Returns a Solr filter condition where the <code>field</code> must be earlier or on or after 
	 * the first of the supplied values.
	 * 
	 * @param field the field to compare
	 * @param values a list of (date) values, only first value will be considered
	 * @param beforeOrAfter indicating if before (<code>true</code>) of on or after (<code>false</code>) condition will be composed
	 * @return the Solr time range condition
	 */
	private String composeTimeRangeCondition(String field, List<String>  values, boolean beforeOrAfter) {
		String date = (values != null && !values.isEmpty())? values.get(0): "*";
		StringBuilder sb = new StringBuilder();
		if (beforeOrAfter) {
			sb.append(field + ":{* " + " TO " + date + "}");
		} else {
			sb.append(field + ":[" + date + " TO " + "*}");
		}
		return sb.toString();
	}
    
	/**
	 * Find and returns DRIS entry by various kind of filters.
	 * Examples of queries this method answer:
	 * <ul>
	 * 	<li><code>entries?country.id=country00725</code></li>
	 *  <li><code>entries?country.id=country00725&country.id=country00732</code></li>
	 *  <li><code>entries?country.id=country00725,country00732</code></li>
	 *  <li><code>entries?country.id=country00725&max-page-size=10</code></li>
	 *  <li><code>entries?country.id=country00725&max-page-size=10&page-start-doc=10</code></li>
	 * </ul>
	 * The Solr fields corresponding to filter criteria was configured in 'dris-rest.cfg', the method uses default values for any missed configuration. 
	 * 
	 * @param queryParameters all the parameters passed as regular http parameters by the caller
	 * @param maxPageSize the maximum number of results to return 
	 * @param startPageDocNumb the start document index in the result sets to start with 
	 * @return the list of <code>org.dspace.app.webui.cris.util.JsonLdEntry</code> resulting from the query
	 * @throws ServletException if the supplied parameters are not supported for this kind of call or in case of other kind of errors
	 */
	private List<JsonLdEntry> processEntriesQueryTypeWithFilteringParameters(LinkedHashMap<String, List<String>> queryParameters,
																  Integer maxPageSize, Integer startPageDocNumb) throws ServletException {
		log.info(SERVLET_DESCRIPTION+":"+ "Query of type 'entries' started...");
		log.debug(SERVLET_DESCRIPTION+":"+"Parameters: " + queryParameters.toString());
		if (queryParameters.isEmpty()) {
			// If no http parameters was supplied than all entries was requested
			// Actually here is impossible (this case was processed by caller method)
			return this.processAllEntriesQueryType(maxPageSize, startPageDocNumb);
		}
		// Extract the provided parameters, at least one is present
		List<String> paramsNames = new ArrayList<>(queryParameters.keySet());
		// Analyze provided parameters and (multi) values and define the Solr
		// query filtering conditions, one for any distinct param name (logic AND) 
		// and containing one sub-condition for any value of the name (logic OR)
		List<String> filteringConditions = new LinkedList<>();
		for (String pName: paramsNames) {
			List<String> pValues = this.extractParameterValuesFromEventualUris(queryParameters.get(pName));
			if (pName.startsWith("country")) {
				// Filtering by COUNTRY
				if (pName.equals("country.id")) {
					filteringConditions.add(this.composeORFilteringCondition(configurationService.getPropertyAsType(this.getFiltersSettingsPrefix() + "country.id", "crisdris.driscountry_authority"), pValues));
				} else if (pName.equals("country.code.alpha2")) {
					filteringConditions.add(this.composeORFilteringCondition(configurationService.getPropertyAsType(this.getFiltersSettingsPrefix() + "country.code.alpha2", "crisdris.driscountry.countryalphacode2"), pValues));
				} else if (pName.equals("country.name")) {
					filteringConditions.add(this.composeORFilteringCondition(configurationService.getPropertyAsType(this.getFiltersSettingsPrefix() + "country.name", "crisdris.driscountry"), pValues));
				} else {
					String errMsg = "Unrecognized criteria for country filtering: " + pName + "=" + pValues;
					log.error(SERVLET_DESCRIPTION +":"+  errMsg);
					throw new ServletException(errMsg);
				}
			} else if (pName.startsWith("status")) {
				// Filtering by STATUS
				if (pName.equals("status.id")) {
					filteringConditions.add(this.composeORFilteringCondition(configurationService.getPropertyAsType(this.getFiltersSettingsPrefix() + "status.id", "crisdris.drisstatus_authority"), pValues));
				} else if (pName.equals("status.label")) {
					filteringConditions.add(this.composeORFilteringCondition(configurationService.getPropertyAsType(this.getFiltersSettingsPrefix() + "status.label", "crisdris.drisstatus"), pValues));
				} else {
					String errMsg = "Unrecognized criteria for status filtering: " + pName + "=" + pValues;
					log.error(SERVLET_DESCRIPTION +":"+  errMsg);
					throw new ServletException(errMsg);
				}
			} else if (pName.startsWith("scope")) {
				// Filtering by SCOPE
				if (pName.equals("scope.id")) {
					filteringConditions.add(this.composeORFilteringCondition(configurationService.getPropertyAsType(this.getFiltersSettingsPrefix() + "scope.id", "crisdris.drisscope_authority"), pValues));
				} else if (pName.equals("scope.label")) {
					filteringConditions.add(this.composeORFilteringCondition(configurationService.getPropertyAsType(this.getFiltersSettingsPrefix() + "scope.label", "crisdris.drisscope"), pValues));
				} else {
					String errMsg = "Unrecognized criteria for scope filtering: " + pName + "=" + pValues;
					log.error(SERVLET_DESCRIPTION +":"+ errMsg);
					throw new ServletException(errMsg);
				}
			} else if (pName.startsWith("coverage")) {
				// Filtering by COVERAGE
				if (pName.equals("coverage.id")) {
					filteringConditions.add(this.composeORFilteringCondition(configurationService.getPropertyAsType(this.getFiltersSettingsPrefix() + "coverage.id", "crisdris.driscoverage_authority"), pValues));
				} else if (pName.equals("coverage.label")) {
					filteringConditions.add(this.composeORFilteringCondition(configurationService.getPropertyAsType(this.getFiltersSettingsPrefix() + "coverage.label", "crisdris.driscoverage"), pValues));
				} else {
					String errMsg = "Unrecognized criteria for coverage filtering: " + pName + "=" + pValues;
					log.error(SERVLET_DESCRIPTION +":"+ errMsg);
					throw new ServletException(errMsg);
				}
			} else if (pName.startsWith("cris-platform")) {
				// Filtering by CRIS-PLATFORM
				if (pName.equals("cris-platform.id")) {
					filteringConditions.add(this.composeORFilteringCondition(configurationService.getPropertyAsType(this.getFiltersSettingsPrefix() + "cris-platform.id", "crisdris.drissoftware_authority"), pValues));
				} else if (pName.equals("cris-platform.label")) {
					filteringConditions.add(this.composeORFilteringCondition(configurationService.getPropertyAsType(this.getFiltersSettingsPrefix() + "cris-platform.label", "crisdris.drissoftware"), pValues));
				} else {
					String errMsg = "Unrecognized criteria for cris-platform filtering: " + pName + "=" + pValues;
					log.error(SERVLET_DESCRIPTION +":"+  errMsg);
					throw new ServletException(errMsg);
				}
			} else if (pName.startsWith("name")) {
				// Filtering by NAME
				if (pName.equals("name")) {
					filteringConditions.add(this.composeORFilteringCondition(configurationService.getPropertyAsType(this.getFiltersSettingsPrefix() + "name", "crisdo.name"), pValues));
				} else {
					String errMsg = "Unrecognized criteria for name filtering: " + pName + "=" + pValues;
					log.error(SERVLET_DESCRIPTION +":"+  errMsg);
					throw new ServletException(errMsg);
				}
			} else if (pName.startsWith("organization")) {
				// Filtering by ORGANIZATION
				if (pName.equals("organization.name")) {
					filteringConditions.add(this.composeORFilteringCondition(configurationService.getPropertyAsType(this.getFiltersSettingsPrefix() + "organization.name", "crisdris.drisproviderOrgUnit"), pValues));
				} else {
					String errMsg = "Unrecognized criteria for organization filtering: " + pName + "=" + pValues;
					log.error(SERVLET_DESCRIPTION +":"+  errMsg);
					throw new ServletException(errMsg);
				}
			} else if (pName.startsWith("established")) {
				// Filtering by ESTABLISHING time
				if (pName.equals("established.before")) {
					filteringConditions.add(this.composeTimeRangeCondition(configurationService.getPropertyAsType(this.getFiltersSettingsPrefix() + "established.before", "drisdateestablished_dt"), pValues, true));
				} else if (pName.equals("established.on-or-after")) {
					filteringConditions.add(this.composeTimeRangeCondition(configurationService.getPropertyAsType(this.getFiltersSettingsPrefix() + "established.on-or-after", "drisdateestablished_dt"), pValues, false));
				} else {
					String errMsg = "Unrecognized criteria for establishing time filtering: " + pName + "=" + pValues;
					log.error(SERVLET_DESCRIPTION +":"+  errMsg);
					throw new ServletException(errMsg);
				}
			} else if (pName.startsWith("created")) {
				// Filtering by CREATION time
				// start date crisdris.drisstartdate......
				if (pName.equals("created.before")) {
					filteringConditions.add(this.composeTimeRangeCondition(configurationService.getPropertyAsType(this.getFiltersSettingsPrefix() + "created.before", "crisdris.time_creation_dt"), pValues, true));
				} else if (pName.equals("created.on-or-after")) {
					filteringConditions.add(this.composeTimeRangeCondition(configurationService.getPropertyAsType(this.getFiltersSettingsPrefix() + "created.on-or-after", "crisdris.time_creation_dt"), pValues, false));
				} else {
					String errMsg = "Unrecognized criteria for creation time filtering: " + pName + "=" + pValues;
					log.error(SERVLET_DESCRIPTION +":"+  errMsg);
					throw new ServletException(errMsg);
				}
			} else if (pName.startsWith("last-modified")) {
				// Filtering by LAST MODIFICATION time
				if (pName.equals("last-modified.before")) {
					filteringConditions.add(this.composeTimeRangeCondition(configurationService.getPropertyAsType(this.getFiltersSettingsPrefix() + "last-modified.before", "crisdris.time_lastmodified_dt"), pValues, true));
				} else if (pName.equals("last-modified.on-or-after")) {
					filteringConditions.add(this.composeTimeRangeCondition(configurationService.getPropertyAsType(this.getFiltersSettingsPrefix() + "last-modified.on-or-after", "crisdris.time_lastmodified_dt"), pValues, false));
				} else {
					String errMsg = "Unrecognized criteria for last modification time filtering: " + pName + "=" + pValues;
					log.error(SERVLET_DESCRIPTION +":"+  errMsg);
					throw new ServletException(errMsg);
				}
			} else {
				// ignore unknow filtering parameters (included those for pagination)
				continue;
			}	
		}
		SolrDocumentList solrResults = new SolrDocumentList();
		SolrQuery solrQuery = new SolrQuery();
		QueryResponse rsp;
		try {
			solrQuery = new SolrQuery();
			solrQuery.setQuery("crisdo.type:dris");
			for (String fq: filteringConditions) {
				solrQuery.addFilterQuery(fq);
			}
			solrQuery.setRows(maxPageSize);
			solrQuery.setStart(startPageDocNumb);
			rsp = this.getCrisSearchService().search(solrQuery);
			solrResults = rsp.getResults();
			Iterator<SolrDocument> iter = solrResults.iterator();
			ObjectMapper objectMapper = new ObjectMapper();
			objectMapper.registerModule(new JsonldModule());
			List<JsonLdEntry> listJldObj = new LinkedList<>();
			while (iter.hasNext()) {
				listJldObj.add(JsonLdEntry.buildFromSolrDoc(this.getCrisSearchService(), iter.next()));
			}
			return listJldObj;
		} catch (SearchServiceException e) {
			ServletException servExc = new ServletException(e);
			log.error(e.getMessage(), servExc);
			throw servExc;
		}	
	}
	
	/**
	 * Find and returns all DRIS entries.
	 * Examples of queries this method answer:
	 * <ul>
	 * 	<li><code>entries</code></li>
	 *  <li><code>entries?max-page-size=10</code></li>
	 *  <li><code>entries?max-page-size=10&page-start-doc=10</code></li>
	 * </ul>
	 * 
	 * @param maxPageSize the maximum number of results to return 
	 * @param startPageDocNumb the start document index in the result sets to start with 
	 * @return the list of <code>org.dspace.app.webui.cris.util.JsonLdEntry</code> resulting from the query
	 * @throws ServletException if the supplied parameters are not supported for this kind of call or in case of other kind of errors
	 */
	private List<JsonLdEntry> processAllEntriesQueryType(Integer maxPageSize, Integer startPageDocNumb) throws ServletException {
		log.info(SERVLET_DESCRIPTION +":"+ "Query of type 'entries' (all) started...");
		SolrDocumentList solrResults = new SolrDocumentList();
		SolrQuery solrQuery = new SolrQuery();
		QueryResponse rsp;
		try {
			solrQuery = new SolrQuery();
			solrQuery.setQuery("crisdo.type:dris");
			solrQuery.setRows(maxPageSize);
			solrQuery.setStart(startPageDocNumb);
			rsp = this.getCrisSearchService().search(solrQuery);
			solrResults = rsp.getResults();
			Iterator<SolrDocument> iter = solrResults.iterator();
			try {
				// VALUTARE QUALI INFORMAZIONI (E COME) VANNO EFFETTIVAMENTE RIPORTATI NEL JSON-LD
				ObjectMapper objectMapper = new ObjectMapper();
				objectMapper.registerModule(new JsonldModule());
				List<JsonLdEntry> list = new LinkedList<>();
				while (iter.hasNext()) {
					list.add(JsonLdEntry.buildFromSolrDoc(this.getCrisSearchService(), iter.next()));
				}
				if (list.size() != 1) {
					String errMsg = "Warning: no organizational unit or too much organizational units was found by the specified ID.";
					log.warn(SERVLET_DESCRIPTION +":"+ errMsg);
				}
				return list;
			} catch (Exception e) {
				log.error(SERVLET_DESCRIPTION +":"+ e.getMessage(), e);
				throw new ServletException(e);
			}
		} catch (SearchServiceException e) {
			log.error(SERVLET_DESCRIPTION +":"+ e.getMessage(), e);
			throw new ServletException(e);
		}
	}
	
	/**
	 * Find and returns a specific DRIS Organizational Unit by its id.
	 * Examples of queries this method answer:
	 * <ul>
	 * 	<li><code>orgunits/ou00102</code></li>
	 * </ul>
	 *  
	 * @param queryParameters the String array containing any parameters (that is, elements of the called path) after the call base url
	 * 						  (a single path parameter, the Organizationa Unit id, is supported)
	 * 
	 * @return the list of <code>org.dspace.app.webui.cris.util.JsonLdOrganizationalUnit</code> resulting from the query
	 * @throws ServletException if the supplied parameters are not supported for this kind of call
	 */
	private List<JsonLdOrganizationalUnit> processOrgUnitsQueryType(String[] queryParameters, Integer maxPageSize, Integer startPageDocNumb) throws ServletException {
		log.info(SERVLET_DESCRIPTION +":"+ "Query of type 'orgunits' started...");
		log.debug(SERVLET_DESCRIPTION +":"+ "Parameters in path: " + queryParameters.toString());		
		if ((queryParameters.length != 1) || StringUtils.isEmpty(queryParameters[0])) {
			// ERROR: exactly one (not null or empty) parameter, the org-unit ID, was expected
			String errorMsg = "Organization Units querying: a single mandatory parameter, the organizational unit id, was expected.";
			log.error(SERVLET_DESCRIPTION +":"+ errorMsg);
			throw new ServletException(errorMsg);
		}
		SolrDocumentList solrResults = new SolrDocumentList();
		String orgUnitId = queryParameters[0];
		SolrQuery solrQuery = new SolrQuery();
		QueryResponse rsp;
		try {
			// This commented code represents the same query made below but querying the database rather than the Solr repository
			//    Researcher util = new Researcher();
			//    ApplicationService applicationService = util.getApplicationService();
			//    applicationService.getEntityByCrisId(orgUnitId);
			solrQuery = new SolrQuery();
			solrQuery.setQuery("*:*");
			solrQuery.addFilterQuery("cris-id:\"" + orgUnitId + "\"");
			solrQuery.addFilterQuery("search.resourcetype:" + CrisConstants.OU_TYPE_ID);
			solrQuery.setRows(maxPageSize);
			solrQuery.setStart(startPageDocNumb);
			rsp = this.getCrisSearchService().search(solrQuery);
			solrResults = rsp.getResults();
			Iterator<SolrDocument> iter = solrResults.iterator();
			try {
				// VALUTARE QUALI INFORMAZIONI (E COME) VANNO EFFETTIVAMENTE RIPORTATI NEL JSON-LD
				ObjectMapper objectMapper = new ObjectMapper();
				objectMapper.registerModule(new JsonldModule());
				List<JsonLdOrganizationalUnit> list = new LinkedList<>();
				while (iter.hasNext()) {
					list.add(JsonLdOrganizationalUnit.buildFromSolrDoc(iter.next()));
				}
				if (list.size() != 1) {
					String errMsg = "Warning: no organizational unit or too much organizational units was found by the specified ID.";
					log.warn(SERVLET_DESCRIPTION +":"+ errMsg);
				}
				return list;
			} catch (Exception e) {
				log.error(SERVLET_DESCRIPTION +":"+ e.getMessage(), e);
				throw new ServletException(e);
			}
		} catch (SearchServiceException e) {
			log.error(SERVLET_DESCRIPTION +":"+ e.getMessage(), e);
			throw new ServletException(e);
		}
	}
	
	/**
	 * Check validity of vocabs sub-type specified by the caller (i.e. 'coverages', 'statuses', etc.)
	 * 
	 * @param vocabQueryType the supplied vocabs sub-type
	 * @return <code>true</code> if the sub-type is valid, <code>false</code> otherwise
	 */
	private Boolean checkVocabQuerySubTypeValidity(String vocabQueryType) {
		if (vocabQueryType.equals(VOCABS_QUERY_TYPE_COUNTRIES_SUB_TYPE) ||
			vocabQueryType.equals(VOCABS_QUERY_TYPE_COVERAGES_SUB_TYPE) ||
			vocabQueryType.equals(VOCABS_QUERY_TYPE_CRIS_PLATFORMS_SUB_TYPE) ||
			vocabQueryType.equals(VOCABS_QUERY_TYPE_SCOPES_SUB_TYPE) ||
			vocabQueryType.equals(VOCABS_QUERY_TYPE_STATUSES_SUB_TYPE)) {
			return true;
		} else return false;
	}
	
	/**
	 * Map the vocabs sub-type specified by the caller (i.e. 'coverages', 'statuses', etc.)
	 * in the corresponding keyword to be used for querying (i.e. 'coverage', 'status', etc.)
	 * 
	 * @param vocabQueryType the supplied vocabs sub-type
	 * @return the corresponding keyword to be used for querying
	 * @throws ServletException if the supplied vocabs sub-type was not recognized
	 */
	private String mapVocabQuerySubTypeToVocabRepositorySelector(String vocabQueryType) throws ServletException {
		if (vocabQueryType.equals(VOCABS_QUERY_TYPE_COVERAGES_SUB_TYPE)) {
			return "coverage";
		} else if (vocabQueryType.equals(VOCABS_QUERY_TYPE_CRIS_PLATFORMS_SUB_TYPE)) {
			return "cris-platform";
		} else if (vocabQueryType.equals(VOCABS_QUERY_TYPE_SCOPES_SUB_TYPE)) {
			return "scope";
		} else if (vocabQueryType.equals(VOCABS_QUERY_TYPE_STATUSES_SUB_TYPE)) {
			return "status";
		} else {
			// ERROR: unrecognized vocab_code
			String errorMsg = "Vocabs querying: unrecognized sub-type parameter: " + vocabQueryType;
			log.error(SERVLET_DESCRIPTION +":"+ errorMsg);
			throw new ServletException(errorMsg);	
		}
	}
	
	/**
	 * Find and returns DRIS <code>/vocabs</code> of various sub-type.
	 * Examples of queries this method answer:
	 * <ul>
	 * 	<li><code>vocabs</code></li>
	 * 	<li><code>vocabs?max-page-size=10</code></li>
	 * 	<li><code>vocabs?max-page-size=10&page-start-doc=10</code></li>
	 * 	<li><code>vocabs/countries</code></li>
	 * 	<li><code>vocabs/scopes</code></li>
	 * 	<li><code>vocabs/statuses</code></li>
	 * 	<li><code>vocabs/coverages</code></li>
	 * 	<li><code>vocabs/cris-platforms</code></li>
	 * 	<li><code>vocabs/countries?max-page-size=10</code></li>
	 * 	<li><code>vocabs/countries/country00725</code></li>
	 * 	<li><code>vocabs/scopes/classcerif00069</code></li> 
	 * </ul>
	 * 
	 * @param queryParameters the String array containing any parameters (that is, elements of the called path) after the call base url
	 * @param maxPageSize the maximum number of results to return 
	 * @param startPageDocNumb the start document index in the result sets to start with 
	 * @return the list of <code>org.dspace.app.webui.cris.util.JsonLdVocabs</code> resulting from the query
	 * @throws ServletException if the supplied parameters are not supported for this kind of call or in case of other kind of errors
	 */
	private List<JsonLdVocabs> processVocabsQueryType(String[] queryParameters, Integer maxPageSize, Integer startPageDocNumb) throws ServletException {
		log.info(SERVLET_DESCRIPTION + ": Query of type 'vocabs' started...");
		log.debug(SERVLET_DESCRIPTION + ": Parameters in path: " + queryParameters.toString());			
		String firstParameter = "";
		if (queryParameters.length == 0) {
			// No sub-type specified, all vocabs requested
			return this.processAllVocabsQueryType(maxPageSize, startPageDocNumb);
		} else {
			firstParameter = queryParameters[0];
			if (firstParameter.equals(VOCABS_QUERY_TYPE_COUNTRIES_SUB_TYPE)) {
				// The 'countries' sub-type was specified in the path
				return this.processVocabsQueryTypeCountrySubType(this.removeFirstItem(queryParameters), maxPageSize, startPageDocNumb);
			} else if (this.checkVocabQuerySubTypeValidity(firstParameter)) {
				// Some valid sub-type other than 'countries' was specified in the path
				// We pass all parameters, the sub-method had to recognize specific sub-type
				return this.processVocabsQueryTypeOtherSubType(queryParameters, maxPageSize, startPageDocNumb);
			} else {
				// Unrecognized query sub-type, throw an error
				ServletException servExc = new ServletException("Unrecognized query sub-type: " + firstParameter);
				log.error(servExc, servExc);
				throw servExc;
			}
		}
	}
	
	/**
	 * Find and returns all DRIS <code>/vocabs</code>  without other sub type path elements and
	 * without any other parameter (i.e. the caller wants all vocabs).
	 * Examples of queries this method answer:
	 * <ul>
	 * 	<li><code>vocabs</code></li>
	 * </ul>
	 * 
	 * @param maxPageSize the maximum number of results to return 
	 * @param startPageDocNumb the start document index in the result sets to start with 
	 * @return the list of <code>org.dspace.app.webui.cris.util.JsonLdVocabs</code> resulting from the query
	 * @throws ServletException if the supplied parameters are not supported for this kind of call or in case of other kind of errors
	 */	
	private List<JsonLdVocabs> processAllVocabsQueryType(Integer maxPageSize, Integer startPageDocNumb) throws ServletException {
		// All vocabs requested
		SolrDocumentList solrResults = new SolrDocumentList();
		SolrQuery solrQuery = new SolrQuery();
		QueryResponse rsp;
		try {
			solrQuery = new SolrQuery();
			solrQuery.setQuery("(crisdo.type:country) OR (crisdo.type:classcerif AND crisclasscerif.classcerifvocabularytype:coverage) "
												   + "OR (crisdo.type:classcerif AND crisclasscerif.classcerifvocabularytype:cris-platform) "
												   + "OR (crisdo.type:classcerif AND crisclasscerif.classcerifvocabularytype:scope) "
												   + "OR (crisdo.type:classcerif AND crisclasscerif.classcerifvocabularytype:status)");
			solrQuery.setRows(maxPageSize);
			solrQuery.setStart(startPageDocNumb);
			rsp = this.getCrisSearchService().search(solrQuery);
			solrResults = rsp.getResults();
			Iterator<SolrDocument> iter = solrResults.iterator();
			ObjectMapper objectMapper = new ObjectMapper();
			objectMapper.registerModule(new JsonldModule());
			List<JsonLdVocabs> listJldObj = new LinkedList<>();
			while (iter.hasNext()) {
				listJldObj.add(JsonLdVocabs.buildFromSolrDoc(iter.next()));
			}
			return listJldObj;
		} catch (SearchServiceException e) {
			ServletException servExc = new ServletException(e);
			log.error(e.getMessage(), servExc);
			throw servExc;
		}	
	}
	
	/**
	 * Find and returns all DRIS <code>/vocabs/countries</code>.
	 * Examples of queries this method answer:
	 * <ul>
	 * 	<li><code>vocabs/countries</code></li>
	 * 	<li><code>vocabs/countries?max-page-size=10</code></li>
	 * 	<li><code>vocabs/countries/country00725</code></li>
	 * </ul>
	 * 
	 * @param queryParameters the String array containing any parameters (that is, elements of the called path) after the call base url
	 * @param maxPageSize the maximum number of results to return 
	 * @param startPageDocNumb the start document index in the result sets to start with 
	 * @return the list of <code>org.dspace.app.webui.cris.util.JsonLdVocabs</code> resulting from the query
	 * @throws ServletException if the supplied parameters are not supported for this kind of call or in case of other kind of errors
	 */	
	private List<JsonLdVocabs> processVocabsQueryTypeCountrySubType(String[] queryParameters, Integer maxPageSize, Integer startPageDocNumb) throws ServletException {
		SolrDocumentList solrResults = new SolrDocumentList();
		SolrQuery solrQuery = new SolrQuery();
		QueryResponse rsp;
		try {
			solrQuery = new SolrQuery();
			String filterOnItemCode = "";
			if (queryParameters.length > 0) {
				filterOnItemCode = " AND " + "cris-id:\"" + queryParameters[0] + "\"";
			}
			solrQuery.setQuery("crisdo.type:country" + filterOnItemCode); // crisdo.type:dris
			solrQuery.setRows(maxPageSize);
			solrQuery.setStart(startPageDocNumb);
			rsp = this.getCrisSearchService().search(solrQuery);
			solrResults = rsp.getResults();
			Iterator<SolrDocument> iter = solrResults.iterator();
			ObjectMapper objectMapper = new ObjectMapper();
			objectMapper.registerModule(new JsonldModule());
			List<JsonLdVocabs> listJldObj = new LinkedList<>();
			while (iter.hasNext()) {
				listJldObj.add(JsonLdVocabs.buildFromSolrDoc(iter.next()));
			}
			return listJldObj;
		} catch (SearchServiceException e) {
			ServletException servExc = new ServletException(e);
			log.error(e.getMessage(), servExc);
			throw servExc;
		}
	}
	
	/**
	 * Processes requests of type <code>/vocabs/coverages</code>, <code>/vocabs/statuses</code>, <code>/vocabs/scopes</code> and
	 * <code>/vocabs/cris-platforms</code>.
	 * 
	 * @param queryParameters the String array containing any parameters (that is, elements of the called path) after the call base url
	 * @param maxPageSize the maximum number of results to return 
	 * @param startPageDocNumb the start document index in the result sets to start with 
	 * @return the list of <code>org.dspace.app.webui.cris.util.JsonLdVocabs</code> resulting from the query
	 * @throws ServletException if the supplied parameters are not supported for this kind of call or in case of other kind of errors
	 */
	private List<JsonLdVocabs> processVocabsQueryTypeOtherSubType(String[] queryParameters, Integer maxPageSize, Integer startPageDocNumb) throws ServletException {
		SolrDocumentList solrResults = new SolrDocumentList();
		SolrQuery solrQuery = new SolrQuery();
		QueryResponse rsp;
		try {
			String givenSubType = this.mapVocabQuerySubTypeToVocabRepositorySelector(queryParameters[0]);
			solrQuery = new SolrQuery();
			solrQuery.setQuery("*:*");
			solrQuery.addFilterQuery("crisdo.type:classcerif");
			solrQuery.addFilterQuery("crisclasscerif.classcerifvocabularytype:" + givenSubType);
			if (queryParameters.length > 1) {
				solrQuery.addFilterQuery("cris-id:\"" + queryParameters[1] + "\"");
			}
			solrQuery.setRows(maxPageSize);
			solrQuery.setStart(startPageDocNumb);
			rsp = this.getCrisSearchService().search(solrQuery);
			solrResults = rsp.getResults();
			Iterator<SolrDocument> iter = solrResults.iterator();
			ObjectMapper objectMapper = new ObjectMapper();
			objectMapper.registerModule(new JsonldModule());
			List<JsonLdVocabs> listJldObj = new LinkedList<>();
			while (iter.hasNext()) {
				listJldObj.add(JsonLdVocabs.buildFromSolrDoc(iter.next()));
			}
			return listJldObj;
		} catch (SearchServiceException e) {
			ServletException servExc = new ServletException(e);
			log.error(e.getMessage(), servExc);
			throw servExc;
		} 
	}
	
	/**
	 * Sets the <code>org.dspace.app.cris.discovery.CrisSearchService</code> object.
	 * 
	 * @param searchService the <code>org.dspace.app.cris.discovery.CrisSearchService</code> to set
	 */
	public void setCrisSearchService(SearchService searchService) {
		this.searchService = searchService;
	}

	/**
	 * Gets the <code>org.dspace.app.cris.discovery.CrisSearchService</code> object.
	 * 
	 * @return the <code>org.dspace.app.cris.discovery.CrisSearchService</code> of the servlet
	 */
	public SearchService getCrisSearchService() {
		return this.searchService;
	}
	
	/**
	 * Gets the main API URL
	 * 
	 * @return the main API URL
	 */
	public static String getMainApiUrl() {
		return API_URL;
	}

	/**
	 * Sets the the main API URL
	 * 
	 * @param the main API URL
	 */
	public static void setMainApiUrl(String apiUrl) {
		API_URL = apiUrl;
	}

	/**
	 * @return
	 */
	public String getFiltersSettingsPrefix() {
		return filtersSettingsPrefix;
	}

	/**
	 * 
	 * @param filtersSettingsPrefix
	 */
	public void setFiltersSettingsPrefix(String filtersSettingsPrefix) {
		this.filtersSettingsPrefix = filtersSettingsPrefix;
	}
	
}
