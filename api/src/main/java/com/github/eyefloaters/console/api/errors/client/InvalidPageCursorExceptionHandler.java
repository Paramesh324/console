package com.github.eyefloaters.console.api.errors.client;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ext.Provider;

import org.jboss.logging.Logger;

import com.github.eyefloaters.console.api.model.Error;
import com.github.eyefloaters.console.api.support.ErrorCategory;

@Provider
@ApplicationScoped
public class InvalidPageCursorExceptionHandler extends AbstractClientExceptionHandler<InvalidPageCursorException> {

    private static final Logger LOGGER = Logger.getLogger(InvalidPageCursorExceptionHandler.class);

    public InvalidPageCursorExceptionHandler() {
        super(ErrorCategory.InvalidQueryParameter.class, null, null);
    }

    @Override
    public boolean handlesException(Throwable thrown) {
        return thrown instanceof InvalidPageCursorException;
    }

    @Override
    protected List<Error> buildErrors(InvalidPageCursorException exception) {
        return exception.getSources()
            .stream()
            .map(source -> {
                Error error = category.createError(exception.getMessage(), exception, source);
                LOGGER.debugf(exception, "error=%s", error);
                return error;
            })
            .toList();
    }

}
