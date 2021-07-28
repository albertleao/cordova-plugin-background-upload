package com.spoon.backgroundfileupload;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.sromku.simple.storage.SimpleStorage;
import com.sromku.simple.storage.Storage;
import com.sromku.simple.storage.helpers.OrderType;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class FileTransferBackground extends CordovaPlugin {

    private static final String TAG = "FileTransferBackground";
    public static final String WORK_TAG_UPLOAD = "work_tag_upload";

    private CallbackContext uploadCallback;
    private boolean ready = false;

    private Data httpClientBaseConfig = Data.EMPTY;

    public void sendCallback(JSONObject obj) {
        /* we check the webview has been initialized */
        if (ready) {
            PluginResult result = new PluginResult(PluginResult.Status.OK, obj);
            result.setKeepCallback(true);
            uploadCallback.sendPluginResult(result);
        }
    }

    private void sendProgress(final String id, int progressPercent) {
        // Event throttling is now done in UploadTask$ProgressRequestBody#writeTo()

        try {
            sendCallback(new JSONObject()
                    .put("id", id)
                    .put("state", "UPLOADING")
                    .put("progress", progressPercent)
            );
        } catch (JSONException e) {
            // Can't really happen but just in case
            e.printStackTrace();
        }
    }

    private void sendSuccess(final String id, final String response, int statusCode) {
        if (response != null && !response.isEmpty()) {
            logMessage("eventLabel='Uploader onSuccess' uploadId='" + id + "' response='" + response.substring(0, Math.min(2000, response.length() - 1)) + "'");
        } else {
            logMessage("eventLabel='Uploader onSuccess' uploadId='" + id + "' response=''");
        }

        try {
            sendCallback(new JSONObject()
                    .put("id", id)
                    .put("eventId", id)
                    .put("state", "UPLOADED")
                    .put("serverResponse", response)
                    .put("statusCode", statusCode)
            );
        } catch (JSONException e) {
            // Can't really happen but just in case
            e.printStackTrace();
        }
    }

    private void sendError(final String id, final String reason, boolean userCanceled) {
        logMessage("eventLabel='Uploader onError' uploadId='" + id + "' error='" + reason + "'");

        try {
            sendCallback(new JSONObject()
                    .put("id", id)
                    .put("eventId", id)
                    .put("state", "FAILED")
                    .put("error", "upload failed: " + reason)
                    .put("errorCode", userCanceled ? -999 : 0)
            );
        } catch (JSONException e) {
            // Can't really happen but just in case
            e.printStackTrace();
        }
    }

    @Override
    public boolean execute(String action, JSONArray args, final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(() -> {
            try {
                switch (action) {
                    case "initManager":
                        initManager(args.get(0).toString(), callbackContext);
                        break;
                    case "destroy":
                        destroy();
                        break;
                    case "startUpload":
                        addUpload(args.getJSONObject(0));
                        break;
                    case "removeUpload":
                        removeUpload(args.get(0).toString(), callbackContext);
                        break;
                    case "acknowledgeEvent":
                        acknowledgeEvent(args.getString(0), callbackContext);
                        break;
                }
            } catch (Exception exception) {
                String message = "(" + exception.getClass().getSimpleName() + ") - " + exception.getMessage();
                PluginResult result = new PluginResult(PluginResult.Status.ERROR, message);
                result.setKeepCallback(true);
                callbackContext.sendPluginResult(result);
                exception.printStackTrace();
            }
        });

        return true;
    }

    private void initManager(String options, final CallbackContext callbackContext) throws IllegalStateException {
        if (this.ready) {
            throw new IllegalStateException("initManager was called twice");
        }

        try {
            final JSONObject settings = new JSONObject(options);
            int ccUpload = settings.getInt("parallelUploadsLimit");

            // Rebuild base HTTP config
            httpClientBaseConfig = new Data.Builder()
                    .putInt(UploadTask.KEY_INPUT_CONFIG_CONCURRENT_DOWNLOADS, ccUpload)
                    .build();
        } catch (JSONException e) {
            logMessage("eventLabel='Uploader could not read parallelUploadsLimit from config' error='" + e.getMessage() + "'");
        }

        // Register notification channel if the android version requires it
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager notificationManager = (NotificationManager) cordova.getContext().getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.createNotificationChannel(new NotificationChannel(
                    UploadTask.NOTIFICATION_CHANNEL_ID,
                    UploadTask.NOTIFICATION_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
            ));
        }

        final AckDatabase ackDatabase = AckDatabase.getInstance(cordova.getContext());

        // Resend pending ACK at startup (and warmup database)
        final List<UploadEvent> uploadEvents = ackDatabase
                .uploadEventDao()
                .getAll();

        for (UploadEvent ack : uploadEvents) {
            handleAck(ack.getOutputData());
        }

        // Can't use observeForever anywhere else than the main thread
        cordova.getActivity().runOnUiThread(() -> {
            // Listen for upload progress
            WorkManager.getInstance(cordova.getContext())
                    .getWorkInfosByTagLiveData(WORK_TAG_UPLOAD)
                    .observeForever((tasks) -> {
                        for (WorkInfo info : tasks) {
                            switch (info.getState()) {
                                // If the upload in not finished, publish its progress
                                case RUNNING:
                                    if (info.getProgress() != Data.EMPTY) {
                                        String id = info.getProgress().getString(UploadTask.KEY_PROGRESS_ID);
                                        int progress = info.getProgress().getInt(UploadTask.KEY_PROGRESS_PERCENT, 0);

                                        Log.d(TAG, "initManager: " + info.getId() + " (" + info.getState() + ") Progress: " + progress);
                                        sendProgress(id, progress);
                                    }
                                    break;
                                case BLOCKED:
                                case ENQUEUED:
                                case SUCCEEDED:
                                    // No db in main thread
                                    cordova.getThreadPool().execute(() -> {
                                        // The corresponding ACK is already in the DB, if it not, the task is just a leftover
                                        String id = info.getOutputData().getString(UploadTask.KEY_OUTPUT_ID);
                                        if (ackDatabase.uploadEventDao().exists(id)) {
                                            handleAck(info.getOutputData());
                                        }
                                    });
                                    break;
                                case FAILED:
                                    // The task can't fail completely so something really bad has happened.
                                    logMessage("eventLabel='Uploader failed inexplicably' error='" + info.getOutputData() + "'");
                                    break;
                            }
                        }
                    });
        });

        this.uploadCallback = callbackContext;
        this.ready = true;

        // mark v1 uploads as failed
        migrateOldUploads();
    }

    private void addUpload(JSONObject jsonPayload) {
        // Get payload
        HashMap<String, Object> payload = null;
        try {
            payload = FileTransferBackground.convertToHashMap(jsonPayload);
        } catch (JSONException error) {
            logMessage("eventLabel='Uploader could not read id from payload' error:'" + error.getMessage() + "'");
        }
        if (payload == null) return;

        // Prepare task payload

        final String uploadId = String.valueOf(payload.get("id"));

        // Create headers
        final Map<String, Object> headers;
        try {
            headers = convertToHashMap((JSONObject) payload.get("headers"));
        } catch (JSONException e) {
            logMessage("eventLabel='could not parse request headers' uploadId='" + uploadId + "' error='" + e.getMessage() + "'");
            sendAddingUploadError(uploadId, e);
            return;
        }
        final List<String> headersNames = new ArrayList<>(headers.size());
        final Map<String, Object> headerValues = new HashMap<>(headers.size());
        for (Map.Entry<String, Object> entry : headers.entrySet()) {
            headerValues.put(UploadTask.KEY_INPUT_HEADER_VALUE_PREFIX + headersNames.size(), entry.getValue());
            headersNames.add(entry.getKey());
        }

        // Create parameters
        final Map<String, Object> parameters;
        try {
            parameters = convertToHashMap((JSONObject) payload.get("parameters"));
        } catch (JSONException e) {
            logMessage("eventLabel='could not parse request headers' uploadId='" + uploadId + "' error='" + e.getMessage() + "'");
            sendAddingUploadError(uploadId, e);
            return;
        }
        final List<String> parameterNames = new ArrayList<>(parameters.size());
        final Map<String, Object> parameterValues = new HashMap<>(parameters.size());
        for (Map.Entry<String, Object> entry : parameters.entrySet()) {
            parameterValues.put(UploadTask.KEY_INPUT_PARAMETER_VALUE_PREFIX + parameterNames.size(), entry.getValue());
            parameterNames.add(entry.getKey());
        }

        startUpload(uploadId, new Data.Builder()
                // Put base info
                .putString(UploadTask.KEY_INPUT_ID, uploadId)
                .putString(UploadTask.KEY_INPUT_URL, (String) payload.get("serverUrl"))
                .putString(UploadTask.KEY_INPUT_FILEPATH, (String) payload.get("filePath"))
                .putString(UploadTask.KEY_INPUT_FILE_KEY, (String) payload.get("fileKey"))
                .putString(UploadTask.KEY_INPUT_HTTP_METHOD, (String) payload.get("requestMethod"))

                // Put headers
                .putInt(UploadTask.KEY_INPUT_HEADERS_COUNT, headersNames.size())
                .putStringArray(UploadTask.KEY_INPUT_HEADERS_NAMES, headersNames.toArray(new String[0]))
                .putAll(headerValues)

                // Put query parameters
                .putInt(UploadTask.KEY_INPUT_PARAMETERS_COUNT, parameterNames.size())
                .putStringArray(UploadTask.KEY_INPUT_PARAMETERS_NAMES, parameterNames.toArray(new String[0]))
                .putAll(parameterValues)

                // Put notification stuff
                .putString(UploadTask.KEY_INPUT_NOTIFICATION_TITLE, (String) payload.get("notificationTitle"))
                .putString(UploadTask.KEY_INPUT_NOTIFICATION_ICON, cordova.getActivity().getPackageName() + ":drawable/ic_upload")

                // Put config stuff
                .putAll(httpClientBaseConfig)
                .build()
        );
    }

    private void startUpload(final String uploadId, final Data payload) {
        Log.d(TAG, "startUpload: Starting work via work manager");

        OneTimeWorkRequest workRequest = new OneTimeWorkRequest.Builder(UploadTask.class)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .keepResultsForAtLeast(0, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .addTag(WORK_TAG_UPLOAD)
                .setInputData(payload)
                .build();

        WorkManager.getInstance(cordova.getContext())
                .enqueueUniqueWork(uploadId, ExistingWorkPolicy.KEEP, workRequest);

        logMessage("eventLabel='Uploader starting upload' uploadId='" + uploadId + "'");
    }

    private void sendAddingUploadError(String uploadId, Exception error) {
        try {
            sendCallback(new JSONObject()
                    .put("id", uploadId)
                    .put("state", "FAILED")
                    .put("errorCode", 0)
                    .put("error", error.getMessage())
            );
        } catch (JSONException e) {
            // Will not happen
            e.printStackTrace();
        }
    }

    private void removeUpload(String uploadId, CallbackContext context) {
        logMessage("eventLabel='Remove upload " + uploadId + "'");

        // Cancel the task ...
        WorkManager.getInstance(cordova.getContext())
                .cancelUniqueWork(uploadId)
                .getResult()
                .addListener(() -> {
                    // ... and cleanup eventual ACK and file
                    cleanupUpload(uploadId);

                    PluginResult result = new PluginResult(PluginResult.Status.OK);
                    result.setKeepCallback(true);
                    context.sendPluginResult(result);
                }, cordova.getThreadPool());
    }

    private void acknowledgeEvent(String eventId, CallbackContext context) {
        logMessage("eventLabel='ACK event " + eventId + "'");

        // Cleanup will delete the ACK.
        cleanupUpload(eventId);

        PluginResult result = new PluginResult(PluginResult.Status.OK);
        result.setKeepCallback(true);
        context.sendPluginResult(result);
    }

    /**
     * Handle ACK data and send it to the JS.
     */
    private void handleAck(final Data ackData) {
        // If upload was successful
        if (!ackData.getBoolean(UploadTask.KEY_OUTPUT_IS_ERROR, false)) {
            // Read response from file if present
            String response = null;
            if (ackData.getString(UploadTask.KEY_OUTPUT_RESPONSE_FILE) != null) {
                response = readFileToStringNoThrow(ackData.getString(UploadTask.KEY_OUTPUT_RESPONSE_FILE));
            }

            sendSuccess(
                    ackData.getString(UploadTask.KEY_OUTPUT_ID),
                    response,
                    ackData.getInt(UploadTask.KEY_OUTPUT_STATUS_CODE, -1 /* If this is sent, something is really wrong */)
            );

        } else {
            // The upload was a failure
            sendError(
                    ackData.getString(UploadTask.KEY_OUTPUT_ID),
                    ackData.getString(UploadTask.KEY_OUTPUT_FAILURE_REASON),
                    ackData.getBoolean(UploadTask.KEY_OUTPUT_FAILURE_CANCELED, false)
            );
        }
    }

    /**
     * Cleanup response file and ACK entry.
     */
    private void cleanupUpload(final String uploadId) {
        final UploadEvent ack = AckDatabase.getInstance(cordova.getContext()).uploadEventDao().getById(uploadId);
        // If the upload is done there is an ACK of it, so get file name from there
        if (ack != null) {
            if (ack.getOutputData().getString(UploadTask.KEY_OUTPUT_RESPONSE_FILE) != null) {
                cordova.getContext().deleteFile(ack.getOutputData().getString(UploadTask.KEY_OUTPUT_RESPONSE_FILE));
            }

            // Also delete it from database
            AckDatabase.getInstance(cordova.getContext()).uploadEventDao().delete(ack);
        } else {
            // Otherwise get the data from the task itself
            final WorkInfo task;
            try {
                task = WorkManager.getInstance(cordova.getContext())
                        .getWorkInfosForUniqueWork(uploadId)
                        .get()
                        .get(0);
            } catch (ExecutionException | InterruptedException e) {
                logMessage("eventLabel='Failed to get work info for cleanup (" + uploadId + ")' error='" + e.getMessage() + "'");
                return;
            }

            if (task.getOutputData() != Data.EMPTY && task.getOutputData().getString(UploadTask.KEY_OUTPUT_RESPONSE_FILE) != null) {
                cordova.getContext().deleteFile(task.getOutputData().getString(UploadTask.KEY_OUTPUT_RESPONSE_FILE));
            }
        }
    }

    public void destroy() {
        this.ready = false;
    }

    public void onDestroy() {
        logMessage("eventLabel='Uploader plugin onDestroy'");
        destroy();
    }

    // Old migrations
    // <editor-fold>
    private void migrateOldUploads() {
        Storage storage = SimpleStorage.getInternalStorage(this.cordova.getActivity().getApplicationContext());
        String uploadDirectoryName = "FileTransferBackground";
        if (storage.isDirectoryExists(uploadDirectoryName)) {
            // remove all old uploads
            storage.deleteDirectory(uploadDirectoryName);
        }
    }

    private ArrayList<String> getOldUploadIds() {
        Storage storage = SimpleStorage.getInternalStorage(this.cordova.getActivity().getApplicationContext());
        String uploadDirectoryName = "FileTransferBackground";
        ArrayList<String> previousUploads = new ArrayList();
        List<File> files = storage.getFiles(uploadDirectoryName, OrderType.DATE);
        for (File file : files) {
            if (file.getName().endsWith(".json")) {
                String content = storage.readTextFile(uploadDirectoryName, file.getName());
                if (content != null) {
                    try {
                        previousUploads.add(new JSONObject(content).getString("id"));
                    } catch (JSONException exception) {
                        logMessage("eventLabel='Uploader could not read old uploads' error='" + exception.getMessage() + "'");
                    }
                }
            }
        }
        return previousUploads;
    }
    // </editor-fold>

    @Nullable
    private String readFileToStringNoThrow(final String filename) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(cordova.getContext().openFileInput(filename)))) {
            final StringBuilder builder = new StringBuilder();

            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append("\n");
            }

            // Ok
            return builder.toString();
        } catch (IOException e) {
            return null;
        }
    }

    public static HashMap<String, Object> convertToHashMap(JSONObject jsonObject) throws JSONException {
        HashMap<String, Object> hashMap = new HashMap<>();
        if (jsonObject != null) {
            Iterator<?> keys = jsonObject.keys();
            while (keys.hasNext()) {
                String key = (String) keys.next();
                Object value = jsonObject.get(key);
                hashMap.put(key, value);
            }
        }
        return hashMap;
    }

    public static void logMessage(String message) {
        Log.d("CordovaBackgroundUpload", message);
    }
}
