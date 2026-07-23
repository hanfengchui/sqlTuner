package com.codex.sqltuner.tuning.accuracy;

import com.codex.sqltuner.rule.SqlDialect;
import com.codex.sqltuner.tuning.TuningResult;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 结果校验门面。字段契约、证据引用、SQL 语义和置信度规则由同包协作者分别维护，
 * 这里固定它们的执行顺序，避免模型输出在不同校验阶段得到不同结论。
 */
@Component
public class StrictResultValidator {
    private final ResultStructureValidator structureValidator;
    private final EvidenceReferenceValidator evidenceReferenceValidator;
    private final SqlSemanticValidator sqlSemanticValidator;
    private final IndexCandidateValidator indexCandidateValidator;
    private final ConfidencePolicy confidencePolicy;

    public StrictResultValidator(SqlStatementParser parser) {
        this.evidenceReferenceValidator = new EvidenceReferenceValidator();
        ResultTextSafetyValidator textSafetyValidator = new ResultTextSafetyValidator();
        this.structureValidator = new ResultStructureValidator(evidenceReferenceValidator, textSafetyValidator);
        this.sqlSemanticValidator = new SqlSemanticValidator(parser, evidenceReferenceValidator, textSafetyValidator);
        this.indexCandidateValidator = new IndexCandidateValidator(
                evidenceReferenceValidator, textSafetyValidator, sqlSemanticValidator);
        this.confidencePolicy = new ConfidencePolicy();
    }

    public ValidationOutcome validate(TuningResult result,
                                      ContextPackage context,
                                      SqlStatementProfile originalProfile,
                                      SqlDialect dialect) {
        ValidationOutcome outcome = new ValidationOutcome();
        if (result == null) {
            outcome.reject("模型输出为空");
            return outcome;
        }

        structureValidator.validateRequiredFields(result, outcome);
        Set<String> evidenceIds = evidenceReferenceValidator.evidenceIds(result);
        structureValidator.validateSummaryNarrativeAndDiagnoses(result, evidenceIds, dialect, context, outcome);
        sqlSemanticValidator.validateRewriteCandidates(result, context, originalProfile, dialect, evidenceIds, outcome);
        indexCandidateValidator.validate(result, context, originalProfile, evidenceIds, outcome);
        structureValidator.validateValidationPlan(result, evidenceIds, outcome);
        structureValidator.validateSupplementalText(result, dialect, context, outcome);
        confidencePolicy.capToContext(result, context);
        structureValidator.enforceContextPermissions(result, context, outcome);
        return outcome;
    }
}
