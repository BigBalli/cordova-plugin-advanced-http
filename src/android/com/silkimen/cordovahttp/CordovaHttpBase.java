package com.silkimen.cordovahttp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;

import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLException;

import com.silkimen.http.HttpBodyDecoder;
import com.silkimen.http.HttpRequest;
import com.silkimen.http.HttpRequest.HttpRequestException;
import com.silkimen.http.JsonUtils;
import com.silkimen.http.TLSConfiguration;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.util.Base64;
import android.util.Log;

abstract class CordovaHttpBase implements Runnable {
  protected static final String TAG = "Cordova-Plugin-HTTP";

  protected String method;
  protected String url;
  protected String serializer = "none";
  protected String responseType;
  protected Object data;
  protected JSONObject headers;
  protected int connectTimeout;
  protected int readTimeout;
  protected boolean followRedirects;
  protected TLSConfiguration tlsConfiguration;
  protected CordovaObservableCallbackContext callbackContext;

  public CordovaHttpBase(String method, String url, String serializer, Object data, JSONObject headers, int connectTimeout,
      int readTimeout, boolean followRedirects, String responseType, TLSConfiguration tlsConfiguration,
      CordovaObservableCallbackContext callbackContext) {

    this.method = method;
    this.url = url;
    this.serializer = serializer;
    this.data = data;
    this.headers = headers;
    this.connectTimeout = connectTimeout;
    this.readTimeout = readTimeout;
    this.followRedirects = followRedirects;
    this.responseType = responseType;
    this.tlsConfiguration = tlsConfiguration;
    this.callbackContext = callbackContext;
  }

  public CordovaHttpBase(String method, String url, JSONObject headers, int connectTimeout, int readTimeout, boolean followRedirects,
      String responseType, TLSConfiguration tlsConfiguration, CordovaObservableCallbackContext callbackContext) {

    this.method = method;
    this.url = url;
    this.headers = headers;
    this.connectTimeout = connectTimeout;
    this.readTimeout = readTimeout;
    this.followRedirects = followRedirects;
    this.responseType = responseType;
    this.tlsConfiguration = tlsConfiguration;
    this.callbackContext = callbackContext;
  }

  private static final int MAX_REDIRECTS = 10;

  @Override
  public void run() {
    CordovaHttpResponse response = new CordovaHttpResponse();
    HttpRequest request = null;

    try {
      request = this.createRequest();
      this.prepareRequest(request);
      this.sendBody(request);

      // Manually follow redirects to persist cookies from intermediate responses
      if (this.followRedirects) {
        Map<String, String> collectedCookies = new HashMap<String, String>();
        int redirectCount = 0;

        while (redirectCount < MAX_REDIRECTS) {
          int code = request.code();

          if (code != HttpURLConnection.HTTP_MOVED_PERM
              && code != HttpURLConnection.HTTP_MOVED_TEMP
              && code != HttpURLConnection.HTTP_SEE_OTHER
              && code != 307 && code != 308) {
            break;
          }

          // Collect Set-Cookie headers from the redirect response
          Map<String, List<String>> redirectHeaders = request.headers();
          List<String> setCookieHeaders = new ArrayList<String>();

          for (Map.Entry<String, List<String>> entry : redirectHeaders.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase("Set-Cookie")) {
              setCookieHeaders = entry.getValue();
            }
          }

          for (String cookie : setCookieHeaders) {
            // Extract cookie name=value (before any attributes like ;path=/)
            String nameValue = cookie.split(";")[0].trim();
            String name = nameValue.split("=")[0].trim();
            collectedCookies.put(name, nameValue);
          }

          // Resolve the redirect URL (may be relative)
          String location = request.header("Location");
          if (location == null || location.isEmpty()) {
            break;
          }

          URL currentUrl = request.url();
          URL redirectUrl = new URL(currentUrl, location);
          request.disconnect();

          // Use GET for 301/302/303 redirects per HTTP spec (POST -> GET)
          String redirectMethod = this.method;
          if (code == HttpURLConnection.HTTP_MOVED_PERM
              || code == HttpURLConnection.HTTP_MOVED_TEMP
              || code == HttpURLConnection.HTTP_SEE_OTHER) {
            redirectMethod = "GET";
          }

          request = new HttpRequest(redirectUrl.toString(), redirectMethod);
          request.followRedirects(false);
          request.connectTimeout(this.connectTimeout);
          request.readTimeout(this.readTimeout);
          request.uncompress(true);

          if (this.tlsConfiguration.getHostnameVerifier() != null) {
            request.setHostnameVerifier(this.tlsConfiguration.getHostnameVerifier());
          }
          request.setSSLSocketFactory(this.tlsConfiguration.getTLSSocketFactory());
          request.headers(JsonUtils.getStringMap(this.headers));

          // Inject collected cookies into the redirect request
          if (!collectedCookies.isEmpty()) {
            StringBuilder cookieHeader = new StringBuilder();
            for (String nameValue : collectedCookies.values()) {
              if (cookieHeader.length() > 0) {
                cookieHeader.append("; ");
              }
              cookieHeader.append(nameValue);
            }
            request.header("Cookie", cookieHeader.toString());
          }

          redirectCount++;
        }
      }

      this.processResponse(request, response);
      request.disconnect();
    } catch (HttpRequestException e) {
      Throwable cause = e.getCause();
      String message = cause.getMessage();

      if (cause instanceof SSLException) {
        response.setStatus(-2);
        response.setErrorMessage("TLS connection could not be established: " + e.getMessage());
        Log.w(TAG, "TLS connection could not be established", e);
      } else if (cause instanceof UnknownHostException) {
        response.setStatus(-3);
        response.setErrorMessage("Host could not be resolved: " + e.getMessage());
        Log.w(TAG, "Host could not be resolved", e);
      } else if (cause instanceof SocketTimeoutException) {
        response.setStatus(-4);
        response.setErrorMessage("Request timed out: " + e.getMessage());
        Log.w(TAG, "Request timed out", e);
      } else if (cause instanceof InterruptedIOException && "thread interrupted".equals(message.toLowerCase())) {
        this.setAborted(request, response);
      } else {
        response.setStatus(-1);
        response.setErrorMessage("There was an error with the request: " + message);
        Log.w(TAG, "Generic request error", e);
      }
    } catch (InterruptedException ie) {
      this.setAborted(request, response);
    } catch (Exception e) {
      response.setStatus(-1);
      response.setErrorMessage(e.getMessage());
      Log.e(TAG, "An unexpected error occured", e);
    }

    try {
      if (response.hasFailed()) {
        this.callbackContext.error(response.toJSON());
      } else {
        this.callbackContext.success(response.toJSON());
      }
    } catch (JSONException e) {
      Log.e(TAG, "An unexpected error occured while creating HTTP response object", e);
    }
  }

  protected HttpRequest createRequest() throws JSONException {
    return new HttpRequest(this.url, this.method);
  }

  protected void prepareRequest(HttpRequest request) throws JSONException, IOException {
    // Always disable automatic redirects so we can manually handle them
    // and persist cookies from intermediate redirect responses
    request.followRedirects(false);
    request.connectTimeout(this.connectTimeout);
    request.readTimeout(this.readTimeout);
    request.uncompress(true);

    if (this.tlsConfiguration.getHostnameVerifier() != null) {
      request.setHostnameVerifier(this.tlsConfiguration.getHostnameVerifier());
    }

    request.setSSLSocketFactory(this.tlsConfiguration.getTLSSocketFactory());

    // setup content type before applying headers, so user can override it
    this.setContentType(request);

    request.headers(JsonUtils.getStringMap(this.headers));
  }

  protected void setContentType(HttpRequest request) {
    if ("json".equals(this.serializer)) {
      request.contentType("application/json", "UTF-8");
    } else if ("utf8".equals(this.serializer)) {
      request.contentType("text/plain", "UTF-8");
    } else if ("raw".equals(this.serializer)) {
      request.contentType("application/octet-stream");
    } else if ("urlencoded".equals(this.serializer)) {
      // intentionally left blank, because content type is set in HttpRequest.form()
    } else if ("multipart".equals(this.serializer)) {
      // intentionally left blank, because content type is set in HttpRequest.part()
    }
  }

  protected void sendBody(HttpRequest request) throws Exception {
    if (this.data == null) {
      return;
    }

    if ("json".equals(this.serializer)) {
      request.send(this.data.toString());
    } else if ("utf8".equals(this.serializer)) {
      request.send(((JSONObject) this.data).getString("text"));
    } else if ("raw".equals(this.serializer)) {
      request.send(Base64.decode((String)this.data, Base64.DEFAULT));
    } else if ("urlencoded".equals(this.serializer)) {
      request.form(JsonUtils.getObjectMap((JSONObject) this.data));
    } else if ("multipart".equals(this.serializer)) {
      JSONArray buffers = ((JSONObject) this.data).getJSONArray("buffers");
      JSONArray names = ((JSONObject) this.data).getJSONArray("names");
      JSONArray fileNames = ((JSONObject) this.data).getJSONArray("fileNames");
      JSONArray types = ((JSONObject) this.data).getJSONArray("types");

      for (int i = 0; i < buffers.length(); ++i) {
        byte[] bytes = Base64.decode(buffers.getString(i), Base64.DEFAULT);
        String name = names.getString(i);

        if (fileNames.isNull(i)) {
          request.part(name, new String(bytes, "UTF-8"));
        } else {
          request.part(name, fileNames.getString(i), types.getString(i), new ByteArrayInputStream(bytes));
        }
      }

      // prevent sending malformed empty multipart requests (#372)
      if (buffers.length() == 0) {
        request.contentType("multipart/form-data; boundary=00content0boundary00");
        request.send("\r\n--00content0boundary00--\r\n");
      }
    }
  }

  protected void processResponse(HttpRequest request, CordovaHttpResponse response) throws Exception {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    request.receive(outputStream);

    response.setStatus(request.code());
    response.setUrl(request.url().toString());
    response.setHeaders(request.headers());

    if (request.code() >= 200 && request.code() < 300) {
      if ("text".equals(this.responseType) || "json".equals(this.responseType)) {
        String decoded = HttpBodyDecoder.decodeBody(outputStream.toByteArray(), request.charset());
        response.setBody(decoded);
      } else {
        response.setData(outputStream.toByteArray());
      }
    } else {
      response.setErrorMessage(HttpBodyDecoder.decodeBody(outputStream.toByteArray(), request.charset()));
    }
  }

  protected void setAborted(HttpRequest request, CordovaHttpResponse response) {
    response.setStatus(-8);
    response.setErrorMessage("Request was aborted");

    if (request != null) {
      try {
        request.disconnect();
      } catch(Exception any){
        Log.w(TAG, "Failed to close aborted request", any);
      }
    }

    Log.i(TAG, "Request was aborted");
  }
}
