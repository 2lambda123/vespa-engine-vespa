// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.persistence;

import com.yahoo.security.X509CertificateUtils;
import com.yahoo.slime.Cursor;
import com.yahoo.slime.Slime;
import com.yahoo.slime.SlimeUtils;
import com.yahoo.vespa.hosted.controller.support_access.SupportAccess;
import com.yahoo.vespa.hosted.controller.support_access.SupportAccessChange;
import com.yahoo.vespa.hosted.controller.support_access.SupportAccessGrant;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * (de)serializes support access status and history
 *
 * @author andreer
 */
public class SupportAccessSerializer {

    // WARNING: Since there are multiple servers in a ZooKeeper cluster and they upgrade one by one
    //          (and rewrite all nodes on startup), changes to the serialized format must be made
    //          such that what is serialized on version N+1 can be read by version N:
    //          - ADDING FIELDS: Always ok
    //          - REMOVING FIELDS: Stop reading the field first. Stop writing it on a later version.
    //          - CHANGING THE FORMAT OF A FIELD: Don't do it bro.

    private static final String stateFieldName = "state";
    private static final String supportAccessFieldName = "supportAccess";
    private static final String untilFieldName = "until";
    private static final String byFieldName = "by";
    private static final String historyFieldName = "history";
    private static final String allowedStateName = "allowed";
    private static final String disallowedStateName = "disallowed";
    private static final String atFieldName = "at";
    private static final String grantFieldName = "grants";
    private static final String requestorFieldName = "requestor";
    private static final String notBeforeFieldName = "notBefore";
    private static final String notAfterFieldName = "notAfter";
    private static final String certificateFieldName = "certificate";


    public static Slime toSlime(SupportAccess supportAccess, boolean includeCertificates, Optional<Instant> withCurrentState) {
        Slime slime = new Slime();
        Cursor root = slime.setObject();

        withCurrentState.ifPresent(now -> {
                    Cursor status = root.setObject(stateFieldName);
                    SupportAccess.CurrentStatus currentState = supportAccess.currentStatus(now);
                    status.setString(supportAccessFieldName, currentState.state().name());
                    if (currentState.state() == SupportAccess.State.ALLOWED) {
                        status.setString(untilFieldName, serializeInstant(currentState.allowedUntil().orElseThrow()));
                        status.setString(byFieldName, currentState.allowedBy().orElseThrow());
                    }
                }
        );

        Cursor history = root.setArray(historyFieldName);
        for (SupportAccessChange change : supportAccess.changeHistory()) {
            Cursor historyObject = history.addObject();
            historyObject.setString(stateFieldName, change.accessAllowedUntil().isPresent() ? allowedStateName : disallowedStateName);
            historyObject.setString(atFieldName, serializeInstant(change.changeTime()));
            change.accessAllowedUntil().ifPresent(allowedUntil -> historyObject.setString(untilFieldName, serializeInstant(allowedUntil)));
            historyObject.setString(byFieldName, change.madeBy());
        }

        Cursor grants = root.setArray(grantFieldName);
        for (SupportAccessGrant grant : supportAccess.grantHistory()) {
            Cursor grantObject = grants.addObject();
            grantObject.setString(requestorFieldName, grant.requestor());
            if (includeCertificates) {
                grantObject.setString(certificateFieldName, X509CertificateUtils.toPem(grant.certificate()));
            } else {
                grantObject.setString(notBeforeFieldName, serializeInstant(grant.certificate().getNotBefore().toInstant()));
                grantObject.setString(notAfterFieldName, serializeInstant(grant.certificate().getNotAfter().toInstant()));
            }
        }

        return slime;
    }

    private static String serializeInstant(Instant i) {
        return DateTimeFormatter.ISO_INSTANT.format(i.truncatedTo(ChronoUnit.SECONDS));
    }

    public static SupportAccess fromSlime(Slime slime) {
        List<SupportAccessGrant> grantHistory = SlimeUtils.entriesStream(slime.get().field(grantFieldName))
                .map(inspector ->
                        new SupportAccessGrant(
                                inspector.field(requestorFieldName).asString(),
                                X509CertificateUtils.fromPem(inspector.field(certificateFieldName).asString())
                        ))
                .collect(Collectors.toUnmodifiableList());

        List<SupportAccessChange> changeHistory = SlimeUtils.entriesStream(slime.get().field(historyFieldName))
                .map(inspector ->
                        new SupportAccessChange(
                                SlimeUtils.optionalString(inspector.field(untilFieldName)).map(Instant::parse),
                                Instant.parse(inspector.field(atFieldName).asString()),
                                inspector.field(byFieldName).asString())
                )
                .collect(Collectors.toUnmodifiableList());

        return new SupportAccess(changeHistory, grantHistory);
    }
}
