// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.api;

import app.epistola.client.jakarta.error.ProblemDetailException;
import app.epistola.client.jakarta.error.ProblemDetailParser;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;

/**
 * Turns error responses from the Epistola API into exceptions.
 *
 * <p>Hand-written, replacing the generator's version of this class: an
 * {@code application/problem+json} body becomes a typed {@link ProblemDetailException} the caller
 * can switch on, and anything else becomes the same {@link ApiException} the generator would have
 * raised. Every generated API interface carries
 * {@code @RegisterProvider(ApiExceptionMapper.class)}, so this applies to injected
 * ({@code @Inject @RestClient}) and programmatically built clients alike, with nothing for the
 * consumer to register.
 *
 * <p>Deliberately <em>not</em> annotated {@code @Provider}: a global mapper would also intercept
 * the consumer's own JAX-RS clients and resources, which is not this library's business.
 */
public class ApiExceptionMapper implements ResponseExceptionMapper<ApiException> {

    @Override
    public boolean handles(int status, MultivaluedMap<String, Object> headers) {
        return status >= 400;
    }

    @Override
    public ApiException toThrowable(Response response) {
        if (!ProblemDetailParser.isProblemJson(response.getMediaType())) {
            return new ApiException(response);
        }

        String body = readBody(response);
        ProblemDetailParser.ParsedProblem parsed = ProblemDetailParser.parse(body);
        if (parsed == null) {
            return new ApiException(response);
        }
        return new ProblemDetailException(
                response, parsed.problem(), parsed.errors(), parsed.validationErrors(), body);
    }

    /**
     * Buffers the entity before reading it, so the {@link Response} the caller can still reach
     * through {@link ApiException#getResponse()} has not been consumed by this mapper.
     */
    private static String readBody(Response response) {
        try {
            if (!response.hasEntity()) {
                return null;
            }
            response.bufferEntity();
            return response.readEntity(String.class);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
