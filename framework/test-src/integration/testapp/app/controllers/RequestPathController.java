package controllers;

import org.apache.logging.log4j.ThreadContext;
import play.mvc.Controller;

import java.util.ArrayList;

/**
 * PF-237 fixture for {@link integration.RequestPathFunctionalTest}: what a request sees of the
 * work its path does for it.
 */
public class RequestPathController extends Controller {

    /** The keys of the logging context, in the order a JSON log line writes them. */
    public static void loggingContext() {
        renderJSON(new ArrayList<>(ThreadContext.getImmutableContext().keySet()));
    }

    /** The query-string parameter q, which binding takes from the query string. */
    public static void query(String q) {
        renderText("q=" + q);
    }
}
