package com.yahoo.vespa.hadoop.mapreduce.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.Scanner;

public class VespaHttpClient {

    private final HttpClient httpClient;

    public VespaHttpClient() {
        this(null);
    }

    public VespaHttpClient(VespaConfiguration configuration) {
       httpClient = createClient(configuration);
    }

    public String get(String url) throws IOException {
        HttpGet httpGet = new HttpGet(url);
        HttpResponse httpResponse = httpClient.execute(httpGet);
        if (httpResponse.getStatusLine().getStatusCode() != 200) {
            return null;
        }

        HttpEntity entity = httpResponse.getEntity();
        InputStream is = entity.getContent();

        String result = "";
        Scanner scanner = new Scanner(is, "UTF-8").useDelimiter("\\A");
        if (scanner.hasNext()) {
            result = scanner.next();
        }
        EntityUtils.consume(entity);

        return result;
    }

    public JsonNode parseResultJson(String json) throws IOException {
        if (json == null || json.isEmpty()) {
            return null;
        }
        ObjectMapper m = new ObjectMapper();
        JsonNode node = m.readTree(json);
        if (node != null) {
            node = node.get("root");
            if (node != null) {
                node = node.get("children");
            }
        }
        return node;
    }

    private HttpClient createClient(VespaConfiguration configuration) {
        HttpClientBuilder clientBuilder = HttpClientBuilder.create();

        RequestConfig.Builder requestConfigBuilder = RequestConfig.custom();
        if (configuration != null) {
            requestConfigBuilder.setSocketTimeout(configuration.queryConnectionTimeout());
            requestConfigBuilder.setConnectTimeout(configuration.queryConnectionTimeout());
            if (configuration.proxyHost() != null) {
                requestConfigBuilder.setProxy(new HttpHost(configuration.proxyHost(), configuration.proxyPort()));
            }
        }
        clientBuilder.setDefaultRequestConfig(requestConfigBuilder.build());
        return clientBuilder.build();
    }

}
