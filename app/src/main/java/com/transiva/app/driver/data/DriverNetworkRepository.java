package com.transiva.app.driver.data;

import com.transiva.app.SessionManager;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;

/** Stability 4.1 repository facade: UI code talks to this contract, not transport details. */
public final class DriverNetworkRepository {
    private final DriverApiClient api;
    public DriverNetworkRepository(SessionManager session) { this.api = new DriverApiClient(session); }
    public ExecutorService executor() { return api.executor(); }
    public DriverApiClient.Result get(String endpoint) throws DriverApiClient.ApiException { return api.get(endpoint); }
    public DriverApiClient.Result post(String endpoint, JSONObject payload) throws DriverApiClient.ApiException { return api.post(endpoint, payload); }
    public DriverApiClient.Result postIdempotent(String endpoint, JSONObject payload) throws DriverApiClient.ApiException { return api.postIdempotent(endpoint, payload); }
    public DriverApiClient.Result setState(String endpoint, JSONObject payload) throws DriverApiClient.ApiException { return api.postIdempotent(endpoint, payload); }
    public void close() { api.shutdown(); }
}
