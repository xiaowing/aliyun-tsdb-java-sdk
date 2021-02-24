package com.aliyun.hitsdb.client.callback.http;

import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.List;

import com.alibaba.fastjson.JSONException;
import com.aliyun.hitsdb.client.Config;
import com.aliyun.hitsdb.client.callback.BatchPutIgnoreErrorsCallback;
import com.aliyun.hitsdb.client.exception.http.*;
import com.aliyun.hitsdb.client.value.response.batch.IgnoreErrorsResult;
import org.apache.http.HttpResponse;
import org.apache.http.concurrent.FutureCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;
import com.aliyun.hitsdb.client.callback.AbstractBatchPutCallback;
import com.aliyun.hitsdb.client.callback.BatchPutCallback;
import com.aliyun.hitsdb.client.callback.BatchPutDetailsCallback;
import com.aliyun.hitsdb.client.callback.BatchPutSummaryCallback;
import com.aliyun.hitsdb.client.http.HttpAPI;
import com.aliyun.hitsdb.client.http.HttpAddressManager;
import com.aliyun.hitsdb.client.http.HttpClient;
import com.aliyun.hitsdb.client.http.response.HttpStatus;
import com.aliyun.hitsdb.client.http.response.ResultResponse;
import com.aliyun.hitsdb.client.value.Result;
import com.aliyun.hitsdb.client.value.request.Point;
import com.aliyun.hitsdb.client.value.response.batch.DetailsResult;
import com.aliyun.hitsdb.client.value.response.batch.SummaryResult;

public class BatchPutHttpResponseCallback implements FutureCallback<HttpResponse> {
    private static final Logger LOGGER = LoggerFactory.getLogger(BatchPutHttpResponseCallback.class);

    private final AbstractBatchPutCallback<?> batchPutCallback;
    private final List<Point> pointList;
    private final int batchPutRetryTimes;
    private final boolean compress;
    private final HttpClient hitsdbHttpClient;
    private final Config config;
    private final String address;

    public BatchPutHttpResponseCallback(String address, HttpClient httpclient, AbstractBatchPutCallback<?> batchPutCallback,
                                        List<Point> pointList, Config config, int batchPutRetryTimes) {
        super();
        this.address = address;
        this.hitsdbHttpClient = httpclient;
        this.batchPutCallback = batchPutCallback;
        this.pointList = pointList;
        this.batchPutRetryTimes = batchPutRetryTimes;
        this.compress = config.isHttpCompress();
        this.config = config;
    }

    @Override
    public void completed(HttpResponse httpResponse) {
        try {
            // 处理响应
            if (httpResponse.getStatusLine().getStatusCode() == org.apache.http.HttpStatus.SC_TEMPORARY_REDIRECT) {
                this.hitsdbHttpClient.setSslEnable(true);
                if (errorRetry()) {
                    return;
                }
            }
            ResultResponse resultResponse = ResultResponse.simplify(httpResponse, this.compress);
            HttpStatus httpStatus = resultResponse.getHttpStatus();
            switch (httpStatus) {
                case ServerSuccess:
                case ServerSuccessNoContent:
                    if (batchPutCallback == null) {
                        return;
                    }

                    if (batchPutCallback instanceof BatchPutCallback) {
                        ((BatchPutCallback) batchPutCallback).response(this.address, pointList, new Result());
                        return;
                    } else if (batchPutCallback instanceof BatchPutSummaryCallback) {
                        SummaryResult summaryResult = null;
                        if (!httpStatus.equals(HttpStatus.ServerSuccessNoContent)) {
                            String content = resultResponse.getContent();
                            summaryResult = JSON.parseObject(content, SummaryResult.class);
                        }
                        ((BatchPutSummaryCallback) batchPutCallback).response(this.address, pointList, summaryResult);
                        return;
                    } else if (batchPutCallback instanceof BatchPutDetailsCallback) {
                        DetailsResult detailsResult = null;
                        if (!httpStatus.equals(HttpStatus.ServerSuccessNoContent)) {
                            String content = resultResponse.getContent();
                            detailsResult = JSON.parseObject(content, DetailsResult.class);
                        }
                        ((BatchPutDetailsCallback) batchPutCallback).response(this.address, pointList, detailsResult);
                        return;
                    } else if (batchPutCallback instanceof BatchPutIgnoreErrorsCallback) {
                        IgnoreErrorsResult ignoreErrorsResult = null;
                        if (!httpStatus.equals(HttpStatus.ServerSuccessNoContent)) {
                            String content = resultResponse.getContent();
                            ignoreErrorsResult = JSON.parseObject(content, IgnoreErrorsResult.class);
                        }
                        ((BatchPutIgnoreErrorsCallback) batchPutCallback).response(this.address, pointList, ignoreErrorsResult);
                        return;
                    }
                case ServerBadRequest:
                    HttpServerBadRequestException bdex = new HttpServerBadRequestException(resultResponse);
                    this.failedWithResponse(bdex);
                    return;
                case ServerNotSupport: {
                    // 服务器返回4xx错误
                    HttpServerNotSupportException ex = new HttpServerNotSupportException(resultResponse);
                    this.failedWithResponse(ex);
                    return;
                }
                case ServerError: {
                    // 服务器返回5xx错误
                    if (this.batchPutRetryTimes == 0) {
                        HttpServerErrorException ex = new HttpServerErrorException(resultResponse);
                        this.failedWithResponse(ex);
                    } else {
                        errorRetry();
                    }

                    return;
                }
                default: {
                    HttpUnknowStatusException ex = new HttpUnknowStatusException(resultResponse);
                    this.failedWithResponse(ex);
                }
            }
        } finally {
            // 正常释放Semaphor
            this.hitsdbHttpClient.getSemaphoreManager().release(address);
        }
    }

    /**
     * 有响应的异常处理。
     *
     * @param ex
     * @note  visible for testing only
     */
    void failedWithResponse(Exception ex) {
        if (batchPutCallback == null) { // 无回调逻辑，则失败打印日志。
            LOGGER.error("No callback logic exception. address:" + this.address, ex);
        } else {
            String responseContent = ex.getMessage();
            if ((ex instanceof HttpServerBadRequestException) && (responseContent != null) && (!responseContent.isEmpty())) {
                HttpServerBadRequestException bdex = (HttpServerBadRequestException)ex;
                if (batchPutCallback instanceof BatchPutSummaryCallback) {
                    SummaryResult summaryResult = null;
                    try {
                        summaryResult = JSON.parseObject(responseContent, SummaryResult.class);
                    } catch (JSONException jex) {
                        // not all the 400 error has summary information
                        LOGGER.warn("failed to deserialize {} into SummaryResult", responseContent);
                    }
                    ((BatchPutSummaryCallback) batchPutCallback).partialFailed(this.address, pointList, bdex, summaryResult);
                    return;
                } else if (batchPutCallback instanceof BatchPutDetailsCallback) {
                    DetailsResult detailsResult = null;
                    try {
                        detailsResult = JSON.parseObject(responseContent, DetailsResult.class);
                    } catch (JSONException jex) {
                        // not all the 400 error has detailed information
                        LOGGER.warn("failed to deserialize {} into DetailsResult", responseContent);
                    }
                    ((BatchPutDetailsCallback) batchPutCallback).partialFailed(this.address, pointList, bdex, detailsResult);
                    return;
                }
            }
            batchPutCallback.failed(this.address, pointList, ex);
        }
    }

    private String getNextAddress() {
        HttpAddressManager httpAddressManager = hitsdbHttpClient.getHttpAddressManager();
        String newAddress = httpAddressManager.getAddress();
        return newAddress;
    }

    private boolean errorRetry() {
        String newAddress;
        boolean acquire;
        int retryTimes = this.batchPutRetryTimes;
        while (true) {
            newAddress = getNextAddress();
            acquire = this.hitsdbHttpClient.getSemaphoreManager().acquire(newAddress);
            retryTimes--;
            if (acquire || retryTimes <= 0) {
                break;
            }
        }

        if (retryTimes == 0) {
            this.hitsdbHttpClient.getSemaphoreManager().release(address);
            return false;
        }

        // retry!
        LOGGER.warn("retry put data!");
        HttpResponseCallbackFactory httpResponseCallbackFactory = this.hitsdbHttpClient.getHttpResponseCallbackFactory();

        FutureCallback<HttpResponse> retryCallback;
        if (batchPutCallback != null) {
            retryCallback = httpResponseCallbackFactory.createBatchPutDataCallback(newAddress, this.batchPutCallback, this.pointList, this.config, retryTimes);
        } else {
            retryCallback = httpResponseCallbackFactory.createNoLogicBatchPutHttpFutureCallback(newAddress, this.pointList, this.config, retryTimes);
        }

        String jsonString = JSON.toJSONString(pointList);
        this.hitsdbHttpClient.post(HttpAPI.PUT, jsonString, retryCallback);
        return true;
    }

    @Override
    public void failed(Exception ex) {
        try {
            // 异常重试
            if (ex instanceof SocketTimeoutException) {
                if (this.batchPutRetryTimes == 0) {
                    ex = new HttpClientSocketTimeoutException(ex);
                } else {
                    if (errorRetry()) {
                        return;
                    }
                }
            } else if (ex instanceof java.net.ConnectException) {
                if (this.batchPutRetryTimes == 0) {
                    ex = new HttpClientConnectionRefusedException(this.address, ex);
                } else {
                    if (errorRetry()) {
                        return;
                    }
                }
            }

            // 处理完毕，向逻辑层传递异常并处理。
            if (batchPutCallback == null) {
                LOGGER.error("No callback logic exception.", ex);
            } else {
                batchPutCallback.failed(this.address, pointList, ex);
            }
        } finally {
            // 重试后释放semaphore许可
            this.hitsdbHttpClient.getSemaphoreManager().release(address);
        }
    }

    @Override
    public void cancelled() {
        this.hitsdbHttpClient.getSemaphoreManager().release(this.address);
        LOGGER.info("the HttpAsyncClient has been cancelled");
    }

}