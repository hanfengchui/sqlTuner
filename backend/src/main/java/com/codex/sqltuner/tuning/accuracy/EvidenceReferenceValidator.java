package com.codex.sqltuner.tuning.accuracy;

import com.codex.sqltuner.tuning.TuningResult;
import com.codex.sqltuner.tuning.result.EvidenceItem;

import java.util.HashSet;
import java.util.Set;

final class EvidenceReferenceValidator {
    Set<String> evidenceIds(TuningResult result) {
        Set<String> ids = new HashSet<String>();
        for (EvidenceItem item : result.getEvidenceCatalog()) {
            if (ResultTextSafetyValidator.hasText(item.getId())) {
                ids.add(item.getId());
            }
        }
        return ids;
    }

    void validateRefs(String field, Iterable<String> refs, Set<String> evidenceIds, ValidationOutcome outcome) {
        boolean hasRef = false;
        for (String ref : refs) {
            hasRef = true;
            if (!evidenceIds.contains(ref)) {
                outcome.reject(field + " 引用了不存在的证据: " + ref);
            }
        }
        if (!hasRef) {
            outcome.reject(field + " 缺少 evidenceRefs");
        }
    }
}
