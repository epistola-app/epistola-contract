# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""Validation of template data, and of the contract's own model constraints."""

from epistola_client.validation.schema import (
    MISSING_REQUIRED_MESSAGE,
    ServerTemplateDataValidator,
    TemplateSchemaValidator,
    ValidatingGenerationApi,
    to_validation_failures,
)
from epistola_client.validation.template_data_validator import (
    TemplateDataValidationError,
    TemplateDataValidator,
    ValidationFailure,
)

__all__ = [
    "MISSING_REQUIRED_MESSAGE",
    "ServerTemplateDataValidator",
    "TemplateDataValidationError",
    "TemplateDataValidator",
    "TemplateSchemaValidator",
    "ValidatingGenerationApi",
    "ValidationFailure",
    "to_validation_failures",
]
