"""Cognito Post Confirmation trigger: put every self-signed-up user in the default group.

Without a group the access token carries no "cognito:groups" claim, so the API
maps no role and a brand-new customer gets 403 on every order endpoint.

Only "PostConfirmation_ConfirmSignUp" is handled (a confirmed forgot-password
flow must not touch memberships). AdminAddUserToGroup is idempotent, so Cognito
retries are harmless. Errors are raised on purpose: the confirmation request
fails visibly, the function's Errors metric pages on-call, and the user stays
confirmed (an operator can add the group by hand).

Logs use the Lambda JSON log format (level set by the function's logging_config).

Environment:
  DEFAULT_GROUP  group name, e.g. CUSTOMER
"""

import logging
import os

import boto3

LOGGER = logging.getLogger()

DEFAULT_GROUP = os.environ["DEFAULT_GROUP"]
COGNITO = boto3.client("cognito-idp")


def handler(event, _context):
    if event.get("triggerSource") != "PostConfirmation_ConfirmSignUp":
        return event

    # With e-mail sign-in, userName is a Cognito-generated UUID: no PII in the logs.
    COGNITO.admin_add_user_to_group(
        UserPoolId=event["userPoolId"],
        Username=event["userName"],
        GroupName=DEFAULT_GROUP,
    )
    LOGGER.info("user added to default group", extra={"group": DEFAULT_GROUP, "user": event["userName"]})
    return event
