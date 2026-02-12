package com.sky.synome.changeset;

import com.sky.synome.core.EngineSession;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class ChangesetValidator {

    private static final String DRL_PACKAGE = "com.sky.synome.rules";

    @Inject
    EngineSession engineSession;

    public void validate(Changeset changeset) {
        List<String> errors = new ArrayList<>();

        if (changeset.id() == null) {
            errors.add("Changeset ID is required");
        }
        if (changeset.entries() == null || changeset.entries().isEmpty()) {
            errors.add("Changeset must have at least one entry");
        }

        if (changeset.entries() != null) {
            for (int i = 0; i < changeset.entries().size(); i++) {
                validateEntry(changeset.entries().get(i), i, errors);
            }
        }

        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
    }

    private void validateEntry(ChangesetEntry entry, int index, List<String> errors) {
        String prefix = "entries[" + index + "]: ";

        if (entry.kind() == null) {
            errors.add(prefix + "kind is required");
        }
        if (entry.action() == null) {
            errors.add(prefix + "action is required");
        }
        if (entry.factType() == null || entry.factType().isBlank()) {
            errors.add(prefix + "factType is required");
        } else {
            var factType = engineSession.kieBase().getFactType(DRL_PACKAGE, entry.factType());
            if (factType == null) {
                errors.add(prefix + "unknown factType '" + entry.factType() + "'");
            }
        }

        if (entry.action() == ChangesetAction.DELETE) {
            if (entry.factKey() == null || entry.factKey().isBlank()) {
                errors.add(prefix + "factKey is required for DELETE");
            }
        } else {
            if (entry.factKey() == null || entry.factKey().isBlank()) {
                errors.add(prefix + "factKey is required");
            }
            if (entry.data() == null || entry.data().isEmpty()) {
                errors.add(prefix + "data is required for UPSERT/EMIT");
            }
        }
    }

    public static class ValidationException extends RuntimeException {
        private final List<String> errors;

        public ValidationException(List<String> errors) {
            super("Changeset validation failed: " + String.join("; ", errors));
            this.errors = errors;
        }

        public List<String> errors() {
            return errors;
        }
    }
}
