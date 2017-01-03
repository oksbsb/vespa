// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.feedhandler;

import com.yahoo.clientmetrics.RouteMetricSet;
import com.yahoo.container.jdisc.HttpResponse;
import com.yahoo.container.jdisc.VespaHeaders;
import com.yahoo.documentapi.messagebus.protocol.DocumentProtocol;
import com.yahoo.documentapi.messagebus.protocol.PutDocumentMessage;
import com.yahoo.documentapi.messagebus.protocol.RemoveDocumentMessage;
import com.yahoo.documentapi.messagebus.protocol.UpdateDocumentMessage;
import com.yahoo.feedapi.SharedSender;
import com.yahoo.messagebus.Error;
import com.yahoo.messagebus.Message;
import com.yahoo.messagebus.Reply;
import com.yahoo.search.result.ErrorMessage;
import com.yahoo.text.Utf8String;
import com.yahoo.text.XMLWriter;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;

@SuppressWarnings("deprecation")
public final class FeedResponse extends HttpResponse implements SharedSender.ResultCallback {

    private final static Logger log = Logger.getLogger(FeedResponse.class.getName());
    private final List<ErrorMessage> errorMessages = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private final StringBuilder traces = new StringBuilder();
    private final RouteMetricSet metrics;
    private boolean abortOnError = false;
    private boolean isAborted = false;

    public FeedResponse(RouteMetricSet metrics) {
        super(com.yahoo.jdisc.http.HttpResponse.Status.OK);
        this.metrics = metrics;
    }

    public boolean isAborted() {
        return isAborted;
    }

    public void setAbortOnFeedError(boolean abort) {
        abortOnError = abort;
    }

    @Override
    public void render(OutputStream outputStream) throws IOException {
        if ( ! errorMessages.isEmpty())
            setStatus(VespaHeaders.getStatus(false, errorMessages.get(0), errorMessages.iterator()));

        XMLWriter writer = new XMLWriter(new OutputStreamWriter(outputStream));
        writer.openTag("result");

        if (metrics != null) {
            metrics.printXml(writer, 0, 0);
        }
        if (traces.length() > 0) {
            writer.openTag("trace");
            writer.append(traces);
            writer.closeTag();
        }
        if (!errors.isEmpty()) {
            writer.openTag("errors");
            writer.attribute(new Utf8String("count"), errors.size());

            for (int i = 0; i < errors.size() && i < 10; ++i) {
                writer.openTag("error");
                writer.attribute(new Utf8String("message"), errors.get(i));
                writer.closeTag();
            }
            writer.closeTag();
        }

        writer.closeTag();
        writer.flush();
        outputStream.close();
    }

    @Override
    public java.lang.String getContentType() {
        return "application/xml";
    }

    public String prettyPrint(Message m) {
        if (m instanceof PutDocumentMessage) {
            return "PUT[" + ((PutDocumentMessage)m).getDocumentPut().getDocument().getId() + "] ";
        }
        if (m instanceof RemoveDocumentMessage) {
            return "REMOVE[" + ((RemoveDocumentMessage)m).getDocumentId() + "] ";
        }
        if (m instanceof UpdateDocumentMessage) {
            return "UPDATE[" + ((UpdateDocumentMessage)m).getDocumentUpdate().getId() + "] ";
        }

        return "";
    }

    public boolean handleReply(Reply reply, int numPending) {
        metrics.addReply(reply);
        if (reply.getTrace().getLevel() > 0) {
            String str = reply.getTrace().toString();
            traces.append(str);
            log.info(str);
        }

        if (containsFatalErrors(reply.getErrors())) {
            for (int i = 0; i < reply.getNumErrors(); ++i) {
                Error err = reply.getError(i);
                StringBuilder out = new StringBuilder(prettyPrint(reply.getMessage()));
                out.append("[").append(DocumentProtocol.getErrorName(err.getCode())).append("] ");
                if (err.getService() != null) {
                    out.append("(").append(err.getService()).append(") ");
                }
                out.append(err.getMessage());

                String str = out.toString();
                log.finest(str);
                addError(str);
            }
            isAborted = abortOnError;
            return !abortOnError;
        }
        return numPending > 0;
    }

    public void done() {
        metrics.done();
    }

    public FeedResponse addXMLParseError(String error) {
        errorMessages.add(ErrorMessage.createBadRequest(error));
        errors.add(error);
        return this;
    }

    public FeedResponse addError(String error) {
        errorMessages.add(ErrorMessage.createBadRequest(error));
        errors.add(error);
        return this;
    }

    public List<String> getErrorList() {
        return errors;
    }

    public List<ErrorMessage> getErrorMessageList() {
        return errorMessages;
    }

    private static boolean containsFatalErrors(Stream<Error> errors) {
        return errors.anyMatch(e -> e.getCode() != DocumentProtocol.ERROR_TEST_AND_SET_CONDITION_FAILED);
    }

    public boolean isSuccess() {
        return errors.isEmpty();
    }

}
