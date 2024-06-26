/*
 * Copyright (c) 2022-2023 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.alibaba.higress.console.service;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.entity.BufferedHttpEntity;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHeader;
import org.apache.tomcat.util.http.fileupload.util.Streams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.alibaba.higress.console.client.grafana.GrafanaClient;
import com.alibaba.higress.console.client.grafana.models.Datasource;
import com.alibaba.higress.console.client.grafana.models.DatasourceCreationResult;
import com.alibaba.higress.console.client.grafana.models.GrafanaDashboard;
import com.alibaba.higress.console.client.grafana.models.GrafanaSearchResult;
import com.alibaba.higress.console.client.grafana.models.SearchType;
import com.alibaba.higress.console.constant.SystemConfigKey;
import com.alibaba.higress.console.constant.UserConfigKey;
import com.alibaba.higress.console.controller.dto.DashboardInfo;
import com.alibaba.higress.sdk.exception.BusinessException;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import lombok.extern.slf4j.Slf4j;

/**
 * @author CH3CHO
 */
@Slf4j
@Service
public class DashboardServiceImpl implements DashboardService {

    private static final Set<String> IGNORE_REQUEST_HEADERS =
        ImmutableSet.of("connection", "accept-encoding", "content-length");
    private static final Set<String> IGNORE_RESPONSE_HEADERS =
        ImmutableSet.of("connection", "content-length", "content-encoding", "server", "transfer-encoding");

    private static final String DATASOURCE_UID_PLACEHOLDER = "${datasource.id}";
    private static final String MAIN_DASHBOARD_DATA_PATH = "/dashboard/main.json";
    private static final String LOG_DASHBOARD_DATA_PATH = "/dashboard/logs.json";
    private static final String PROM_DATASOURCE_TYPE = "prometheus";
    private static final String LOKI_DATASOURCE_TYPE = "loki";
    private static final String DATASOURCE_ACCESS = "proxy";

    private static final ExecutorService EXECUTOR =
        new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, new SynchronousQueue<>(),
            new ThreadFactoryBuilder().setDaemon(true).setNameFormat("DashboardService-Initializer-%d").build());

    @Value("${" + SystemConfigKey.DASHBOARD_OVERWRITE_WHEN_STARTUP_KEY + ":"
        + SystemConfigKey.DASHBOARD_OVERWRITE_WHEN_STARTUP_DEFAULT + "}")
    private boolean overwriteWhenStartUp = SystemConfigKey.DASHBOARD_OVERWRITE_WHEN_STARTUP_DEFAULT;

    @Value("${" + SystemConfigKey.DASHBOARD_BASE_URL_KEY + ":}")
    private String apiBaseUrl;

    private URL apiBaseUrlObject;

    @Value("${" + SystemConfigKey.DASHBOARD_USERNAME_KEY + ":" + SystemConfigKey.DASHBOARD_USERNAME_DEFAULT + "}")
    private String username = SystemConfigKey.DASHBOARD_USERNAME_DEFAULT;

    @Value("${" + SystemConfigKey.DASHBOARD_PASSWORD_KEY + ":" + SystemConfigKey.DASHBOARD_PASSWORD_DEFAULT + "}")
    private String password = SystemConfigKey.DASHBOARD_PASSWORD_DEFAULT;

    @Value("${" + SystemConfigKey.DASHBOARD_DATASOURCE_PROM_NAME_KEY + ":"
        + SystemConfigKey.DASHBOARD_DATASOURCE_PROM_NAME_DEFAULT + "}")
    private String promDatasourceName = SystemConfigKey.DASHBOARD_DATASOURCE_PROM_NAME_DEFAULT;

    @Value("${" + SystemConfigKey.DASHBOARD_DATASOURCE_PROM_URL_KEY + ":}")
    private String promDatasourceUrl;

    @Value("${" + SystemConfigKey.DASHBOARD_DATASOURCE_LOKI_NAME_KEY + ":"
        + SystemConfigKey.DASHBOARD_DATASOURCE_LOKI_NAME_DEFAULT + "}")
    private String lokiDatasourceName = SystemConfigKey.DASHBOARD_DATASOURCE_LOKI_NAME_DEFAULT;

    @Value("${" + SystemConfigKey.DASHBOARD_DATASOURCE_LOKI_URL_KEY + ":}")
    private String lokiDatasourceUrl;

    @Value("${" + SystemConfigKey.DASHBOARD_PROXY_CONNECTION_TIMEOUT_KEY + ":"
        + SystemConfigKey.DASHBOARD_PROXY_CONNECTION_TIMEOUT_DEFAULT + "}")
    private int proxyConnectionTimeout = SystemConfigKey.DASHBOARD_PROXY_CONNECTION_TIMEOUT_DEFAULT;

    @Value("${" + SystemConfigKey.DASHBOARD_PROXY_SOCKET_TIMEOUT_KEY + ":"
        + SystemConfigKey.DASHBOARD_PROXY_SOCKET_TIMEOUT_DEFAULT + "}")
    private int proxySocketTimeout = SystemConfigKey.DASHBOARD_PROXY_SOCKET_TIMEOUT_DEFAULT;

    private ConfigService configService;
    private GrafanaClient grafanaClient;
    private CloseableHttpClient realServerClient;
    private String realServerBaseUrl;

    private String mainDashboardConfiguration;
    private GrafanaDashboard configuredMainDashboard;
    private String logDashboardConfiguration;
    private GrafanaDashboard configuredLogDashboard;

    @Resource
    public void setConfigService(ConfigService configService) {
        this.configService = configService;
    }

    @PostConstruct
    public void initialize() {
        try {
            mainDashboardConfiguration = IOUtils.resourceToString(MAIN_DASHBOARD_DATA_PATH, StandardCharsets.UTF_8);
            configuredMainDashboard = GrafanaClient.parseDashboardData(mainDashboardConfiguration);
            logDashboardConfiguration = IOUtils.resourceToString(LOG_DASHBOARD_DATA_PATH, StandardCharsets.UTF_8);
            configuredLogDashboard = GrafanaClient.parseDashboardData(logDashboardConfiguration);
        } catch (IOException e) {
            throw new IllegalStateException("Error occurs when loading dashboard configurations from resource.", e);
        }

        if (isBuiltIn()) {
            try {
                apiBaseUrlObject = new URL(apiBaseUrl);
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException("Invalid dashboard base url: " + apiBaseUrl, e);
            }

            RequestConfig requestConfig = RequestConfig.custom().setConnectTimeout(proxyConnectionTimeout)
                .setSocketTimeout(proxySocketTimeout).build();
            realServerClient =
                HttpClients.custom().setDefaultRequestConfig(requestConfig).disableRedirectHandling().build();
            realServerBaseUrl = apiBaseUrl.substring(0, apiBaseUrl.length() - apiBaseUrlObject.getPath().length());

            grafanaClient = new GrafanaClient(apiBaseUrl, username, password);
            EXECUTOR.submit(new DashboardInitializer(overwriteWhenStartUp));
        }
    }

    @Override
    public DashboardInfo getDashboardInfo() {
        return isBuiltIn() ? getBuiltInDashboardInfo() : getConfiguredDashboardInfo();
    }

    @Override
    public void initializeDashboard(boolean overwrite) {
        if (!isBuiltIn()) {
            throw new IllegalStateException("No built-in dashboard is available.");
        }

        List<Datasource> datasources;
        try {
            datasources = grafanaClient.getDatasources();
        } catch (IOException e) {
            throw new BusinessException("Error occurs when loading datasources from Grafana.", e);
        }
        String promDatasourceUid = configurePrometheusDatasource(datasources);
        String lokiDatasourceUid = configureLokiDatasource(datasources);

        List<GrafanaSearchResult> results;
        try {
            results = grafanaClient.search(null, SearchType.DB, null, null);
        } catch (IOException e) {
            throw new BusinessException("Error occurs when loading dashboard info from Grafana.", e);
        }
        configureDashboard(results, configuredMainDashboard.getTitle(), mainDashboardConfiguration, promDatasourceUid,
            overwrite);
        configureDashboard(results, configuredLogDashboard.getTitle(), logDashboardConfiguration, lokiDatasourceUid,
            overwrite);
    }

    @Override
    public void setDashboardUrl(String url) {
        if (StringUtils.isBlank(url)) {
            throw new IllegalArgumentException("url cannot be null or blank.");
        }
        if (isBuiltIn()) {
            throw new IllegalStateException("Manual dashboard configuration is disabled.");
        }
        configService.setConfig(UserConfigKey.DASHBOARD_URL, url);
    }

    @Override
    public String buildConfigData(String datasourceUid) {
        return buildConfigData(mainDashboardConfiguration, datasourceUid);
    }

    @Override
    public void forwardDashboardRequest(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!isBuiltIn()) {
            throw new IllegalStateException(
                "Dashboard request forward function is only available for built-in dashboard.");
        }

        HttpUriRequest proxyRequest = buildRealServerRequest(request);
        try (CloseableHttpResponse proxyResponse = realServerClient.execute(proxyRequest)) {
            forwardResponse(response, proxyResponse);
        }
    }

    private String configurePrometheusDatasource(List<Datasource> existedDatasources) {
        String datasourceUid = null;
        if (CollectionUtils.isNotEmpty(existedDatasources)) {
            datasourceUid = existedDatasources.stream().filter(ds -> promDatasourceUrl.equals(ds.getUrl())).findFirst()
                .map(Datasource::getUid).orElse(null);
        }
        if (datasourceUid == null) {
            Datasource datasource = new Datasource();
            datasource.setType(PROM_DATASOURCE_TYPE);
            datasource.setName(promDatasourceName);
            datasource.setUrl(promDatasourceUrl);
            datasource.setAccess(DATASOURCE_ACCESS);
            try {
                DatasourceCreationResult result = grafanaClient.createDatasource(datasource);
                if (result.getDatasource() == null) {
                    throw new BusinessException("Creating data source call returns success but no datasource object."
                        + " Message=" + result.getMessage());
                }
                datasourceUid = result.getDatasource().getUid();
            } catch (IOException e) {
                throw new BusinessException("Error occurs when creating Prometheus datasource in Grafana.", e);
            }
        }
        return datasourceUid;
    }

    private String configureLokiDatasource(List<Datasource> existedDatasources) {
        String datasourceUid = null;
        if (CollectionUtils.isNotEmpty(existedDatasources)) {
            datasourceUid = existedDatasources.stream().filter(ds -> lokiDatasourceUrl.equals(ds.getUrl())).findFirst()
                .map(Datasource::getUid).orElse(null);
        }
        if (datasourceUid == null) {
            Datasource datasource = new Datasource();
            datasource.setType(LOKI_DATASOURCE_TYPE);
            datasource.setName(lokiDatasourceName);
            datasource.setUrl(lokiDatasourceUrl);
            datasource.setAccess(DATASOURCE_ACCESS);
            try {
                DatasourceCreationResult result = grafanaClient.createDatasource(datasource);
                if (result.getDatasource() == null) {
                    throw new BusinessException("Creating data source call returns success but no datasource object."
                        + " Message=" + result.getMessage());
                }
                datasourceUid = result.getDatasource().getUid();
            } catch (IOException e) {
                throw new BusinessException("Error occurs when creating Loki datasource in Grafana.", e);
            }
        }
        return datasourceUid;
    }

    private void configureDashboard(List<GrafanaSearchResult> results, String title, String configuration,
        String datasourceUid, boolean overwrite) {
        if (StringUtils.isEmpty(title)) {
            throw new IllegalStateException("No title is found in the configured dashboard.");
        }

        String existedDashboardUid = results.stream().filter(r -> title.equals(r.getTitle()))
            .map(GrafanaSearchResult::getUid).findFirst().orElse(null);
        if (StringUtils.isNotEmpty(existedDashboardUid) && !overwrite) {
            return;
        }

        String dashboardData = buildConfigData(configuration, datasourceUid);
        GrafanaDashboard dashboard;
        try {
            dashboard = GrafanaClient.parseDashboardData(dashboardData);
            dashboard.setId(null);
            dashboard.setUid(null);
            dashboard.setVersion(null);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to parse the configured dashboard data.", e);
        }

        try {
            if (StringUtils.isNotEmpty(existedDashboardUid)) {
                GrafanaDashboard existedDashboard = grafanaClient.getDashboard(existedDashboardUid);
                if (existedDashboard != null) {
                    dashboard.setId(existedDashboard.getId());
                    dashboard.setUid(existedDashboardUid);
                    dashboard.setVersion(existedDashboard.getVersion());
                }
            }
            if (dashboard.getId() == null) {
                grafanaClient.createDashboard(dashboard);
            } else {
                grafanaClient.updateDashboard(dashboard);
            }
        } catch (IOException e) {
            throw new BusinessException("Error occurs when creating Higress dashboard in Grafana.", e);
        }
    }

    private DashboardInfo getBuiltInDashboardInfo() {
        List<GrafanaSearchResult> results;
        try {
            results = grafanaClient.search(null, SearchType.DB, null, null);
        } catch (IOException e) {
            throw new BusinessException("Error occurs when loading dashboard info from Grafana.", e);
        }
        if (CollectionUtils.isEmpty(results)) {
            return new DashboardInfo(true, null, null);
        }
        String expectedTitle = configuredMainDashboard.getTitle();
        if (StringUtils.isEmpty(expectedTitle)) {
            throw new IllegalStateException("No title is found in the configured dashboard.");
        }
        Optional<GrafanaSearchResult> result =
            results.stream().filter(r -> expectedTitle.equals(r.getTitle())).findFirst();
        return result.map(r -> new DashboardInfo(true, r.getUid(), r.getUrl())).orElse(null);
    }

    private DashboardInfo getConfiguredDashboardInfo() {
        String url = configService.getString(UserConfigKey.DASHBOARD_URL);
        return new DashboardInfo(false, null, url);
    }

    private boolean isBuiltIn() {
        return StringUtils.isNoneBlank(apiBaseUrl, promDatasourceUrl, lokiDatasourceUrl);
    }

    private String buildConfigData(String dashboardConfiguration, String datasourceUid) {
        return dashboardConfiguration.replace(DATASOURCE_UID_PLACEHOLDER, datasourceUid);
    }

    private HttpUriRequest buildRealServerRequest(HttpServletRequest originalRequest) throws IOException {
        String servletPath = originalRequest.getServletPath();
        if (!servletPath.startsWith(apiBaseUrlObject.getPath())) {
            throw new IllegalArgumentException("Invalid dashboard request path: " + servletPath);
        }

        String url = realServerBaseUrl + servletPath;
        if (originalRequest.getQueryString() != null) {
            url = url + "?" + originalRequest.getQueryString();
        }

        HttpEntity entity = new BufferedHttpEntity(
            new InputStreamEntity(originalRequest.getInputStream(), originalRequest.getContentLength()));
        HttpUriRequest request =
            RequestBuilder.create(originalRequest.getMethod()).setEntity(entity).setUri(url).build();

        Collections.list(originalRequest.getHeaderNames()).stream()
            .filter(name -> !IGNORE_REQUEST_HEADERS.contains(name.toLowerCase()))
            .forEach(name -> request.setHeader(new BasicHeader(name, originalRequest.getHeader(name))));

        return request;
    }

    private void forwardResponse(HttpServletResponse response, HttpResponse forwardResponse) throws IOException {
        Arrays.stream(forwardResponse.getAllHeaders())
            .filter(header -> !IGNORE_RESPONSE_HEADERS.contains(header.getName().toLowerCase()))
            .forEach(header -> response.setHeader(header.getName(), header.getValue()));
        response.setStatus(forwardResponse.getStatusLine().getStatusCode());
        Streams.copy(forwardResponse.getEntity().getContent(), response.getOutputStream(), false);
    }

    private class DashboardInitializer implements Runnable {

        private final boolean overwrite;

        private DashboardInitializer(boolean overwrite) {
            this.overwrite = overwrite;
        }

        @Override
        public void run() {
            while (!Thread.interrupted()) {
                try {
                    initializeDashboard(overwrite);
                    return;
                } catch (Exception ex) {
                    log.error("Error occurs when trying to initialize the dashboard.", ex);
                    try {
                        TimeUnit.SECONDS.sleep(5);
                    } catch (InterruptedException e) {
                        log.warn("Initialization thread is interrupted.", e);
                    }
                }
            }
        }
    }
}
