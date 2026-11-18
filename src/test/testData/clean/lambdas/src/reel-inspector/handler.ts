// The reel inspector measures one reel on the archive's scanner bench. Invented for the Aslan demo project.
import type { MeasureReelInput, ReelMeasurement } from '../shared/contracts';

/** The scanner bench's client, provided by the archive's runtime layer. */
declare const bench: {
  measure(reelId: string, checks: string[]): Promise<{ shrinkage?: number; acidity?: number; splices?: number }>;
};

export async function handler(input: MeasureReelInput): Promise<ReelMeasurement> {
  const readings = await bench.measure(input.reel.reelId, input.checks);
  return {
    reelId: input.reel.reelId,
    shrinkagePercent: readings.shrinkage ?? 0,
    acidity: readings.acidity ?? 0,
    splices: readings.splices ?? 0,
    measuredAt: new Date().toISOString(),
  };
}
