// The contracts of the film archive's Lambda functions. Invented for the Aslan demo project: the state machine
// definitions beside this folder link to these types from their comments.

/** A reel as the depositor's manifest lists it, after the intake has normalised it. */
export interface Reel {
  reelId: string;
  title: string;
  gauge: string;
  base: 'nitrate' | 'acetate' | 'polyester';
  lengthFeet: number;
  cans: number;
  condition?: string;
}

export type Check = 'shrinkage' | 'acidity' | 'splices';

/** What the reel inspector is asked to measure. */
export interface MeasureReelInput {
  reel: Reel;
  /** Present when the intake of a batch asks; absent for a recheck of a catalogued reel. */
  batchId?: string;
  checks: Check[];
  /** Why a catalogued reel is measured again. */
  reason?: string;
}

/** What the reel inspector measured. */
export interface ReelMeasurement {
  reelId: string;
  shrinkagePercent: number;
  /** Acidity on the archive's A-D strip scale, from 0 to 3. */
  acidity: number;
  splices: number;
  measuredAt: string;
}

/** What the incident reporter is told about a run that stopped. */
export interface IncidentReport {
  incidentKey: string;
  severity: 'high' | 'low';
  step: string;
  error: string;
  cause: string;
  batch: { batchId: string; runId: string };
  catalogWritten: boolean;
  lockHeld: boolean;
  execution: string;
  summary: string;
}

/** The ticket the incident reporter opened. */
export interface IncidentTicket {
  ticketId: string;
  paged: boolean;
}

/** The damaged reels a conservator is asked to assess, with the token that answers the waiting run. */
export interface DamageAssessmentRequest {
  reels: Reel[];
  taskToken: string;
}

/** The conservator's answer, sent back through the task token. */
export interface DamageAssessment {
  /** From 1 (cosmetic) to 5 (the reel is lost without treatment). */
  severity: number;
  urgent: boolean;
  gauge: string;
  stock: string;
  inspectedAt: string;
  /** The S3 key of the vault's inventory report that the restoration queue is filled from. */
  inventoryManifestKey: string;
}

/** A reel of an urgent assessment, handed to the restorer as it is. */
export type RestoreReelInput = Reel;

/** What the restorer did with one reel. */
export interface RestoredReel {
  reelId: string;
  restored: boolean;
  notes?: string;
}
