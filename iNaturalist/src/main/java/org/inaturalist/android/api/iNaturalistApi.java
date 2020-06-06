package org.inaturalist.android.api;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import org.apache.http.NameValuePair;
import org.apache.http.message.BasicNameValuePair;
import org.inaturalist.android.BetterJSONObject;
import org.inaturalist.android.BuildConfig;
import org.inaturalist.android.INaturalistService;
import org.inaturalist.android.api.cbhelpers.ArrayToVoidHelper;
import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.tinylog.Logger;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

// TODO in so many places this uses multipart/form b/c the old API recommended it. However it's still doing
// that for api.inaturalist.org in multiple locations. Needs cleanup
// TODO switch to new API where reasonable
@SuppressWarnings("deprecation")
public class iNaturalistApi {

    private static String TAG = "ServerApi";
    private final ApiHelper mHelper;
    private final String API_HOST;
    private final String HOST;

    /** Allows us to switch the call execution mode to synchronous while running unit tests */
    private final boolean SYNC_FOR_TESTING;

    public iNaturalistApi(String hostUrl, String apiHostUrl, ApiHelper helper) {
        this(hostUrl, apiHostUrl, helper, false);
    }

    @VisibleForTesting
    public iNaturalistApi(String hostUrl, String apiHostUrl, ApiHelper helper, boolean syncForTesting) {
        API_HOST = apiHostUrl;
        HOST = hostUrl;
        mHelper = helper;
        SYNC_FOR_TESTING = syncForTesting;
    }

    /**
     * Temporary interface to allow the API code to call back into the service
     * for stuff that is currently tightly coupled to Android-isms making it hard
     * to extract directly into this non-Android-enabled class
     *
     * Marked everything as Nullable b/c we cannot trust the service with it's various race conditions
     */
    public interface ApiHelper {
        @Nullable String getUserAgent();
        @Nullable String credentials();
        @Nullable String getJWTToken();
        @Nullable String getAnonymousJWTToken();
        @Nullable INaturalistService.LoginType getLoginType();
        boolean ensureCredentials() throws AuthenticationException;
    }

    /**
     * Small data class to hold multiple parts of a response from the server
     * TODO - either refactor other methods to not need this class, or build a small ObjectPool
     */
    public static class ApiResponse {
        int httpResponseCode;
        /** Got HTTP-200 that contained errors field */
        @Nullable JSONArray parsedErrors;
        /** Response content as JSON (if no errors) */
        @Nullable public JSONArray parsedResponse;

        public boolean successful() {
            return parsedResponse != null;
        }
    }

    private Interceptor mLoggingInterceptor = new Interceptor() {
        AtomicInteger mRequestCount = new AtomicInteger();

        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            Response response = null;

            // WARNING - This will print Authorization header directly into log output
            // Do not use this for release builds
            if (BuildConfig.DEBUG) {
                // TODO this idea is nice but does not really work b/c the service is killed/recreated and therefore
                // it kills/rebuilds the API which rebuilds this object. So the id resets to 0 each time the service
                // restarts itself
                int requestId = mRequestCount.getAndIncrement();
                long t1 = System.nanoTime();
                Logger.tag(TAG).debug((String.format(Locale.ENGLISH,
                        "%d: Sending request %d%n%s %s (on %s)%n%s",
                        requestId, requestId,
                        request.method().toUpperCase(), request.url(), chain.connection(),
                        request.headers())));
                response = chain.proceed(request);

                long t2 = System.nanoTime();
                Logger.tag(TAG).debug((String.format(Locale.ENGLISH,
                        "%d: Received response for request %d in %.1fms (URL %s)%n%s",
                        requestId, requestId, (t2 - t1) / 1e6d, response.request().url(),
                        response.headers())));
            } else {
                response = chain.proceed(request);
            }

            return response;
        }
    };

    private final OkHttpClient mClient = new OkHttpClient.Builder()
            .addNetworkInterceptor(mLoggingInterceptor).build();

    public static final MediaType JSON
            = MediaType.parse("application/json; charset=utf-8");
    private static final MediaType OCTET
            = MediaType.parse("application/octet-stream");

    /**
     * @param url what we're doing
     * @param method get, post, put, or delete
     * @param params If non-null, we will send this as the request body with a multipart/form
     * @param jsonContent If non-null, we will send this as the request body with a JSON mimetype
     * @param authenticated
     * @param useJWTToken
     * @param allowAnonymousJWTToken
     * @param cb OkHttp3 callback. The call will be run asynchronously, you must provide a callback
     * @return null if a callback was passed. Otherwise, this method will either return a response or
     *          throw an exception
     * @throws IOException Problem with network (read/write request body, connect to server, etc)
     * @throws ApiError Our code caught some error that's not a network-caused issue. See subclass descriptions
     */
    @Nullable
    private void okHttpRequest(@NonNull String url, @NonNull String method,
                                      @Nullable ArrayList<NameValuePair> params,
                                      @Nullable JSONObject jsonContent, boolean authenticated, boolean useJWTToken,
                                      boolean allowAnonymousJWTToken, @NonNull ApiCallback<JSONArray> cb)
            throws IOException, ApiError {

        if (cb == null)
            throw new IllegalArgumentException("Callback cannot be null");

        // TODO handle POST redirects (301,302,307,308)
        // We would need to check the location (same domain) and protocol (still HTTPS) to be secure
        // Why is this needed anyways? The server should not be doing this...
        // TODO disable content compression for "Faster reading of data"
        //   .disableContentCompression()
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .header("User-Agent", mHelper.getUserAgent());

        Logger.tag(TAG).debug(String.format("OK! URL: %s - %s (params: %s / %s)", method, url, (params != null ? params.toString() : "null"), (jsonContent != null ? jsonContent.toString() : "null")));

        // Sanity checks
        if (jsonContent != null && params != null)
            Logger.tag(TAG).warn("Invalid call, two body types. Sending json and ignoring form data");

        // Building the request body
        RequestBody body = null;
        if (jsonContent != null) {
            requestBuilder.header("Content-type", "application/json");
            body = RequestBody.create(JSON, jsonContent.toString());
        } else if (params != null) {
            // "Standard" multipart encoding

            MultipartBody.Builder multipartBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM);
            for (int i = 0; i < params.size(); i++) {
                String paramName = params.get(i).getName();
                String paramValue = params.get(i).getValue();

                if (paramValue == null) {
                    Logger.tag(TAG).warn("Ignoring parameter with null value: " + paramName);
                    continue;
                }

                // Use FileBody for large data
                if (paramName.equalsIgnoreCase("image")
                        || paramName.equalsIgnoreCase("file")
                        || paramName.equalsIgnoreCase("user[icon]")
                        || paramName.equalsIgnoreCase("audio")) {
                    if (paramName.equalsIgnoreCase("audio")) {
                        String fileExt = paramValue.substring(paramValue.lastIndexOf(".") + 1);
                        MediaType customAudio = MediaType.parse("audio/" + fileExt);
                        multipartBody.addFormDataPart(paramName, paramValue,
                                RequestBody.create(customAudio, new File(paramValue)));
                        // TODO should we not have this on all requests? It's the only thing we parse if 200 is returned
                        requestBuilder.header("Accept", "application/json");
                    } else {
                        multipartBody.addFormDataPart(paramName, paramValue,
                                RequestBody.create(OCTET, new File(paramValue)));
                    }
                } else {
                    // Normal string data
                    multipartBody.addFormDataPart(paramName, paramValue);
                }
            } // End for
            body = multipartBody.build();
        }

        if (body == null) {
            // Work around for okhttp bug which requires body on PUT/POST requests
            body = RequestBody.create(null, "");
        }

        if (method.equalsIgnoreCase("post")) {
            requestBuilder.post(body);
        } else if (method.equalsIgnoreCase("delete")) {
            requestBuilder.delete(body);
        } else if (method.equalsIgnoreCase("put")) {
            requestBuilder.put(body);
        } else {
            requestBuilder.get();
        }

        if (url.startsWith(API_HOST) && (mHelper.credentials() != null)) {
            // For the node API, if we're logged in, *always* use JWT authentication
            authenticated = true;
            useJWTToken = true;
        }

        if (authenticated) {
            if (useJWTToken && allowAnonymousJWTToken && (mHelper.credentials() == null)) {
                // User not logged in, but allow using anonymous JWT
                requestBuilder.addHeader("Authorization", mHelper.getAnonymousJWTToken());
            } else {
                mHelper.ensureCredentials();

                if (useJWTToken) {
                    // Use JSON Web Token for this request
                    requestBuilder.addHeader("Authorization", mHelper.getJWTToken());
                } else if (mHelper.getLoginType() == INaturalistService.LoginType.PASSWORD) {
                    // Old-style password authentication
                    requestBuilder.addHeader("Authorization", "Basic " + mHelper.credentials());
                } else {
                    // OAuth2 token (Facebook/G+/etc)
                    requestBuilder.addHeader("Authorization", "Bearer " + mHelper.credentials());
                }
            }
        }

        Request request = requestBuilder.build();
        Call call = mClient.newCall(request);

        CallbackDecodingAdapter scb = new CallbackDecodingAdapter(cb);
        if (SYNC_FOR_TESTING) {
            // Emulate the normal async operation of callbacks, but use the current thread
            try {
                Response response = call.execute();
                scb.onResponse(call, response);
            } catch (IOException e) {
                cb.onApiError(call, new ApiIoException(e));
            }
        } else {
            call.enqueue(scb);
        }
    }

    /**
     * OkHttp does not decode contents before returning. We want to, b/c this is awesome. This
     * helper class bridges the OkHttp Callback into decoding contents and calling our own
     * ApiCallback with decoded JSON
     */
    private class CallbackDecodingAdapter implements Callback {

        private final ApiCallback<JSONArray> mUserCallback;

        CallbackDecodingAdapter(ApiCallback<JSONArray> userCallback) {
            mUserCallback = userCallback;
        }

        @Override
        public void onFailure(@NotNull Call call,
                @NotNull IOException e) {
            mUserCallback.onApiError(call, new ApiIoException(e));
        }

        @Override
        public void onResponse(@NotNull Call call,
                @NotNull Response response) throws IOException {
            try {
                ApiResponse r = decodeOrThrow(response);
                if (r.successful()) {
                    mUserCallback.onResponse(call, r.parsedResponse);
                } else {
                    // TODO fix this - check if/how external classes depend on seeing the 'errors' key in the JSON body before
                    // I can write a proper test and 100% switch over to throwing an ApiException from within the decodeOrThrow
                    ApiDecodingException ade =
                            new ApiDecodingException("Returned JSON has errors flag set");
                    mUserCallback.onApiError(call, ade);
                }
            } catch (ApiError e) {
                mUserCallback.onApiError(call, e);
            }
        }
    }

    private ApiResponse decodeOrThrow(Response response) throws ApiError, IOException {
        // TODO stop reading entire response body into memory immediately!
        String content =  response.body().string();

        // Cannot debug content in interceptors - you can only read the body once
        Logger.tag(TAG).debug(content);

        JSONArray json;
        int statusCode = response.code();
        ApiResponse ar = new ApiResponse();

        switch (statusCode) {
            case 422:
                // UNPROCESSABLE_ENTITY - server understood request but cannot fulfill it
                // May be server-side validation error - still need to process/return response
                Logger.tag(TAG).error(response.message());
            case HttpURLConnection.HTTP_NO_CONTENT:
                ar.httpResponseCode = statusCode;
                ar.parsedResponse = new JSONArray();
                return ar;
            case HttpURLConnection.HTTP_OK:
                // Two ways of decoding the JSON response (empty vs non-empty content)
                if ((content != null) && (content.length() == 0)) {
                    // In case it's just non content (but OK HTTP status code) - so there's no error
                    json = new JSONArray();
                } else {
                    try {
                        json = new JSONArray(content);
                    } catch (JSONException e) {
                        try {
                            // Wrap single objects in array so we can return one type
                            JSONObject jo = new JSONObject(content);
                            json = new JSONArray();
                            json.put(jo);
                        } catch (JSONException e2) {
                            // We really cannot parse this as JSON
                            ApiDecodingException ade = new ApiDecodingException("Failure decoding response content");
                            ade.initCause(e2);
                            throw ade;
                        }
                    }
                }

                // If we've decoded, double-check that we didn't get an 'errors' key in the response
                if ((json != null) && (json.length() > 0)) {
                    try {
                        JSONObject result = json.getJSONObject(0);
                        if (result.has("errors")) {
                            // Error response
                            Logger.tag(TAG).error("Got an error response: " + result.get("errors").toString());
                            ar.httpResponseCode = statusCode;
                            ar.parsedErrors = result.getJSONArray("errors");
                            return ar;
                        }
                    } catch (JSONException e) {
                        Logger.tag(TAG).error(e);
                    }
                }

                ar.httpResponseCode = statusCode;
                ar.parsedResponse = json;
                return ar;
            case HttpURLConnection.HTTP_UNAUTHORIZED:
                throw new AuthenticationException();
            case HttpURLConnection.HTTP_UNAVAILABLE:
                Logger.tag(TAG).error("503 server unavailable");

                Date mRetryAfterDate = null;

                // Find out if there's a "Retry-After" header
                List<String> headers = response.headers("Retry-After");
                if ((headers != null) && (headers.size() > 0)) {
                    for (String header : headers) {
                        Logger.tag(TAG).error("Retry after raw string: " + header);
                        try {
                            SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz");
                            mRetryAfterDate = format.parse(header);
                            Logger.tag(TAG).error("Retry after: " + mRetryAfterDate);
                            break;
                        } catch (ParseException e) {
                            Logger.tag(TAG).warn(e);
                            try {
                                // Try parsing it as a seconds-delay value
                                int secondsDelay = Integer.parseInt(header);
                                Logger.tag(TAG).error("Retry after: " + secondsDelay);
                                Calendar calendar = Calendar.getInstance();
                                calendar.add(Calendar.SECOND, secondsDelay);
                                mRetryAfterDate = calendar.getTime();
                                break;
                            } catch (NumberFormatException exc) {
                                Logger.tag(TAG).warn(exc);
                            }
                        }
                    }

                    // TODO handle case where mRetryAfterDate is still null b/c we failed to parse it
                }
                throw new ServerError(mRetryAfterDate);
            case HttpURLConnection.HTTP_GONE:
                Logger.tag(TAG).error("GONE: " + response.message());
                // TODO create notification that informs user some observations have been deleted on the server,
                // click should take them to an activity that lets them decide whether to delete them locally
                // or post them as new observations
            default:
                // TODO should we check the code here and stop treating unexpected 2xx as failures?
                Logger.tag(TAG).error(response.message());
                throw new ApiError("Unknown response code: " + statusCode);
        }
    }

    public void addFavorite(int observationId, ApiCallback<JSONArray> cb) {
        String url = HOST + "/votes/vote/observation/" + observationId + ".json";
        postAsync(url, null, null, true, cb);
    }

    public void getTaxonSuggestions(Locale deviceLocale, String photoFilename,
                                                Double latitude, Double longitude,
                                                Timestamp observedOn,
                                                final ApiCallback<BetterJSONObject> callback) {
        String deviceLanguage = deviceLocale.getLanguage();
        String date = observedOn != null ? new SimpleDateFormat("yyyy-MM-dd").format(observedOn) : null;
        ArrayList<NameValuePair> params = new ArrayList<>();
        String url = API_HOST + "/computervision/score_image";

        params.add(new BasicNameValuePair("locale", deviceLanguage));
        params.add(new BasicNameValuePair("lat", latitude.toString()));
        params.add(new BasicNameValuePair("lng", longitude.toString()));
        if (date != null) params.add(new BasicNameValuePair("observed_on", date));
        params.add(new BasicNameValuePair("image", photoFilename));

        ApiCallback<JSONArray> bridgeCb = new ApiCallback<JSONArray>() {
            @Override
            public void onApiError(Call call, ApiError e) {
                callback.onApiError(call, e);
            }

            @Override
            public void onResponse(Call call, JSONArray json) {
                if (json == null || json.length() == 0) {
                    callback.onApiError(call, new ApiDecodingException("Null or empty json array"));
                    return;
                }

                JSONObject res;
                try {
                    res = (JSONObject) json.get(0);
                    if (!res.has("results")) {
                        callback.onApiError(call, new ApiDecodingException("JSON object missing required key 'results'"));
                        return;
                    }
                    callback.onResponse(call, new BetterJSONObject(res));
                } catch (JSONException e) {
                    ApiDecodingException ade = new ApiDecodingException("Failed to decode json");
                    ade.initCause(e);
                    callback.onApiError(call, ade);
                }
            }
        };

        postAsync(url, null, params, true, bridgeCb);
    }

    public void removeFavorite(int observationId, ApiCallback<JSONArray> cb) {
        String url = HOST + "/votes/unvote/observation/" + observationId + ".json";
        deleteAsync(url, null, cb);
    }

    // double minx, double maxx, double miny, double maxy,

    public void getNearbyObservations(double lat, double lng,
                                      double minx, double maxx, double miny, double maxy,
                                      boolean authenticated,
                                      int page, int perPage,
                                       @Nullable String username,
                                       @Nullable Integer taxonId,
                                       @Nullable Integer locationId,
                                       @Nullable Integer projectId,
                                       @NonNull String deviceLanguage,
                                      ApiCallback<JSONArray> cb) {
        String url = HOST;
        if (username != null) {
            url += "/observations/" + username + ".json?extra=observation_photos";
        } else {
            url += "/observations.json?extra=observation_photos";
        }
        url += "&captive=false&page=" + page + "&per_page=" + perPage;
        url += "&locale=" + deviceLanguage;

        if (taxonId != null) url += "&taxon_id=" + taxonId;
        // TODO if this is passed as an array, who is concat'ing it?
        if (projectId != null) url += "&projects[]=" + projectId;


        if (locationId != null ) url += "&place_id=" + locationId;
        else if (lat != 0 && lng != 0){
            url += "&lat=" + lat;
            url += "&lng=" + lng;
        } else {
            url += "&swlat=" + miny;
            url += "&nelat=" + maxy;
            url += "&swlng=" + minx;
            url += "&nelng=" + maxx;
        }

        getAsync(url, authenticated, cb);
    }

    public void pinLocation(double latitude, double longitude, double accuracy,
                            String geoprivacy,
                            String title, ApiCallback<JSONArray> cb) {
        ArrayList<NameValuePair> params = new ArrayList<>();
        params.add(new BasicNameValuePair("saved_location[latitude]", Double.toString(latitude)));
        params.add(new BasicNameValuePair("saved_location[longitude]", Double.toString(longitude)));
        params.add(new BasicNameValuePair("saved_location[positional_accuracy]", Double.toString(accuracy)));
        params.add(new BasicNameValuePair("saved_location[geoprivacy]", geoprivacy));
        params.add(new BasicNameValuePair("saved_location[title]", title));

        String url = HOST + "/saved_locations.json";
        postAsync(url, null, params, true, cb);
    }

    public void deletePinnedLocation(String pinnedId, ApiCallback<JSONArray> callback) {
        String url = HOST + "/saved_locations/" + pinnedId + ".json";
        deleteAsync(url, null, callback);
    }

    public void addComment(int observationId, String body, final ApiCallback<Void> callback) {
        ArrayList<NameValuePair> params = new ArrayList<>();
        params.add(new BasicNameValuePair("comment[parent_id]", Integer.valueOf(observationId).toString()));
        params.add(new BasicNameValuePair("comment[parent_type]", "Observation"));
        params.add(new BasicNameValuePair("comment[body]", body));

        String url = HOST + "/comments.json";
        postAsync(url, null, params, true, new ArrayToVoidHelper(callback));
    }

    public void deleteComment(int commentId, final ApiCallback<Void> callback) {
        String url = HOST + "/comments/" + commentId + ".json";
        deleteAsync(url, null, new ArrayToVoidHelper(callback));
    }

    public ApiResponse syncRequest(String url, String method, ArrayList<NameValuePair> params,
                                   JSONObject jsonContent, boolean authenticated, boolean useJWTToken,
                                   boolean allowAnonymousJWTToken) throws IOException, ApiError {

        SynchronizeCallback sb = new SynchronizeCallback();
        asyncRequest(url, method, params, jsonContent, authenticated, useJWTToken,
                allowAnonymousJWTToken, sb);
        try {
            sb.countDownLatch.await();
        } catch (InterruptedException e) {
            ApiError ae = new ApiError("Synchronous call interrupted");
            ae.initCause(e);
            throw ae;
        }
        if (sb.error != null)
            throw sb.error;
        return sb.response;
    }

    static class SynchronizeCallback implements ApiCallback<JSONArray> {
        ApiError error = null;
        ApiResponse response = null;
        CountDownLatch countDownLatch = new CountDownLatch(1);

        @Override
        public void onApiError(Call call, ApiError e) {
            error = e;
            countDownLatch.countDown();
        }

        @Override
        public void onResponse(Call call, JSONArray json) {
            response = new ApiResponse();
            response.parsedResponse = json;
            response.httpResponseCode = 200;
            countDownLatch.countDown();
        }
    };

    private void putAsync(String url, JSONObject jsonContent, ArrayList<NameValuePair> params,
                          ApiCallback<JSONArray> cb) {
        if (params != null) params.add(new BasicNameValuePair("_method", "PUT"));
        requestAsyncAllParams(url, "put", params, jsonContent, true, cb);
    }

    private void deleteAsync(String url, ArrayList<NameValuePair> params, ApiCallback<JSONArray> cb) {
        requestAsyncAllParams(url, "delete", params, null, true, cb);
    }

    private void postAsync(String url, JSONObject jsonContent, ArrayList<NameValuePair> params,
                           boolean authenticated, ApiCallback<JSONArray> cb) {
        requestAsyncAllParams(url, "post", params, jsonContent, authenticated, cb);
    }

    private void getAsync(String url, boolean authenticated, ApiCallback<JSONArray> cb) {
        requestAsyncAllParams(url, "get", null, null, authenticated, cb);
    }

    private void requestAsyncAllParams(String url, String method, ArrayList<NameValuePair> params,
                                       JSONObject jsonContent, boolean authenticated,
                                       ApiCallback<JSONArray> cb) {
        asyncRequest(url, method, params, jsonContent, authenticated, false, false, cb);
    }

    private void asyncRequest(String url, String method, ArrayList<NameValuePair> params,
                                   JSONObject jsonContent, boolean authenticated, boolean useJWTToken,
                                   boolean allowAnonymousJWTToken, @NonNull ApiCallback<JSONArray> cb) {
        if (cb == null)
            throw new IllegalArgumentException("Callback cannot be null");

        try {
            okHttpRequest(url, method, params, jsonContent, authenticated, useJWTToken,
                    allowAnonymousJWTToken, cb);
        } catch (IOException | ApiError e) {
            // Should never happen. These errors only come from the method decodeOrThrow, which
            // is executed on a background thread if we are using a callback
            Logger.tag(TAG).error("This should never happen", e);
        }
    }
}
