// The damage assessor puts the reels on a conservator's worklist with the task token; the conservator's answer, a
// DamageAssessment, resumes the waiting run. Invented for the Aslan demo project.
import type { DamageAssessmentRequest } from '../shared/contracts';

/** The conservators' worklist, provided by the archive's runtime layer. */
declare const worklist: { add(entry: { reels: unknown[]; answerWith: string }): Promise<void> };

export async function handler(request: DamageAssessmentRequest): Promise<void> {
  await worklist.add({ reels: request.reels, answerWith: request.taskToken });
}
