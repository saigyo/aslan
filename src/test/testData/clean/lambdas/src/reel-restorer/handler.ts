// The reel restorer books one reel into the restoration workshop and reports what was done. Invented for the Aslan
// demo project.
import type { RestoreReelInput, RestoredReel } from '../shared/contracts';

/** The restoration workshop's client, provided by the archive's runtime layer. */
declare const workshop: { restore(reelId: string, condition?: string): Promise<{ done: boolean; notes?: string }> };

export async function handler(reel: RestoreReelInput): Promise<RestoredReel> {
  const job = await workshop.restore(reel.reelId, reel.condition);
  return { reelId: reel.reelId, restored: job.done, notes: job.notes };
}
