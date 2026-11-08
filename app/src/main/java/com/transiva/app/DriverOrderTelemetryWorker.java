package com.transiva.app;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.transiva.app.driver.data.DriverApiClient;
import org.json.JSONObject;

public final class DriverOrderTelemetryWorker extends Worker {
    public DriverOrderTelemetryWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    @NonNull @Override public Result doWork() {
        Context context = getApplicationContext();
        Data data = getInputData();
        long age = System.currentTimeMillis() - data.getLong("recorded", 0);
        if (age > 86400000L || age < -60000L) return Result.success();
        DriverApiClient api = new DriverApiClient(new SessionManager(context), true);
        try {
            // Create the session-pinned client before checking the work item's owner.
            String owner = NavigationSessionStore.identity(context);
            if (owner.isEmpty() || !owner.equals(data.getString("owner"))) return Result.success();
            JSONObject request = new JSONObject().put("client_at_ms", data.getLong("recorded", 0));
            for (String key : new String[]{"source", "order_id", "key", "event", "device_context"}) {
                request.put(key, data.getString(key));
            }
            for (String key : new String[]{"elapsed_ms", "remaining_ms"}) {
                long value = data.getLong(key, -1);
                if (value >= 0) request.put(key, value);
            }
            api.post("driver_order_timeline_receipt.php", request);
            return Result.success();
        } catch (DriverApiClient.ApiException error) {
            boolean transientError = error.status == 0 || error.status == 429 || error.status >= 500;
            return transientError && getRunAttemptCount() < 5 ? Result.retry() : Result.success();
        } catch (org.json.JSONException invalid) {
            return Result.success();
        } finally {
            api.shutdown();
        }
    }
}
