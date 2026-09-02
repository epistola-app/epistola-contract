// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.example.someservice;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;

/**
 * A rest client belonging to some other service, as a consumer's own would be. Deliberately in an
 * unrelated package: {@code EpistolaRestClientListener} runs for every rest client in a deployment,
 * and this is the one it must leave completely alone.
 */
@Path("/hello")
public interface SomeOtherServiceApi {

    @GET
    @Produces("text/plain")
    String hello();
}
