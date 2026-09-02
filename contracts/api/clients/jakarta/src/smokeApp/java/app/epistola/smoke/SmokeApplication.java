// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.smoke;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

/** JAX-RS activation for the deployment smoke application. */
@ApplicationPath("/api")
public class SmokeApplication extends Application {
}
