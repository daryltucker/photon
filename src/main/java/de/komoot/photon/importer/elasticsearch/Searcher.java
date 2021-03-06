package de.komoot.photon.importer.elasticsearch;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import de.komoot.photon.importer.Tags;
import de.komoot.photon.importer.osm.OSMTags;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.text.StrSubstitutor;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.search.SearchHit;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * date: 24.05.14
 *
 * @author christoph
 */
@Slf4j
public class Searcher {
	private final String queryTemplate;
	private final String queryLocationBiasTemplate;
	private final Client client;

	/** These properties are directly copied into the result */
	private final static String[] KEYS_LANG_UNSPEC = {OSMTags.KEY_OSM_ID, OSMTags.KEY_OSM_VALUE, OSMTags.KEY_OSM_KEY, OSMTags.KEY_POSTCODE, OSMTags.KEY_HOUSENUMBER};

	/** These properties will be translated before they are copied into the result */
	private final static String[] KEYS_LANG_SPEC = {OSMTags.KEY_NAME, OSMTags.KEY_COUNTRY, OSMTags.KEY_CITY, OSMTags.KEY_STREET};

	public Searcher(Client client) {
		this.client = client;
		try {
			final ClassLoader loader = Thread.currentThread().getContextClassLoader();
			queryTemplate = IOUtils.toString(loader.getResourceAsStream("query.json"), "UTF-8");
			queryLocationBiasTemplate = IOUtils.toString(loader.getResourceAsStream("query_location_bias.json"), "UTF-8");
		} catch(Exception e) {
			throw new RuntimeException("cannot access query templates", e);
		}
	}

	public List<JSONObject> search(String query, String lang, Double lon, Double lat, int limit, boolean matchAll) {
		final ImmutableMap.Builder<String, Object> params = ImmutableMap.<String, Object>builder()
				.put("query", StringEscapeUtils.escapeJson(query))
				.put("lang", lang)
				.put("should_match", matchAll ? "100%" : "-1");
		if(lon != null) params.put("lon", lon);
		if(lat != null) params.put("lat", lat);

		StrSubstitutor sub = new StrSubstitutor(params.build(), "${", "}");
		if(lon != null && lat != null) {
			query = sub.replace(queryLocationBiasTemplate);
		} else {
			query = sub.replace(queryTemplate);
		}

		SearchResponse response = client.prepareSearch("photon").setSearchType(SearchType.QUERY_AND_FETCH).setQuery(query).setSize(limit).setTimeout(TimeValue.timeValueSeconds(7)).execute().actionGet();
		final SearchHit[] hits = response.getHits().getHits();
		return convert(Arrays.copyOfRange(hits, 0, limit), lang);
	}

	private List<JSONObject> convert(SearchHit[] hits, final String lang) {
		return Lists.transform(Arrays.asList(hits), new Function<SearchHit, JSONObject>() {
			@Nullable
			@Override
			public JSONObject apply(@Nullable SearchHit hit) {
				final Map<String, Object> source = hit.getSource();

				final JSONObject feature = new JSONObject();
				feature.put(Tags.KEY_TYPE, Tags.VALUE_FEATURE);
				feature.put(Tags.KEY_GEOMETRY, getPoint(source));

				final JSONObject properties = new JSONObject();
				// language unspecific properties
				for(String key : KEYS_LANG_UNSPEC) {
					if(source.containsKey(key))
						properties.put(key, source.get(key));
				}

				// language specific properties
				for(String key : KEYS_LANG_SPEC) {
					if(source.containsKey(key))
						properties.put(key, getLocalised(source, key, lang));
				}
				feature.put(Tags.KEY_PROPERTIES, properties);

				return feature;
			}
		});
	}

	private static JSONObject getPoint(Map<String, Object> source) {
		JSONObject point = new JSONObject();

		final Map<String, Double> coordinate = (Map<String, Double>) source.get("coordinate");
		if(coordinate != null) {
			point.put(Tags.KEY_TYPE, Tags.VALUE_POINT);
			point.put(Tags.KEY_COORDINATES, new JSONArray("[" + coordinate.get(Tags.KEY_LON) + "," + coordinate.get(Tags.KEY_LAT) + "]"));
		} else {
			log.error(String.format("invalid data [id=%s, type=%s], coordinate is missing!", source.get(OSMTags.KEY_OSM_ID), source.get(OSMTags.KEY_OSM_VALUE)));
		}

		return point;
	}

	private static String getLocalised(Map<String, Object> source, String fieldName, String lang) {
		final Map<String, String> map = (Map<String, String>) source.get(fieldName);
		if(map == null) return null;

		if(map.get(lang) != null) {
			// language specific field
			return map.get(lang);
		}

		return map.get("default");
	}
}
