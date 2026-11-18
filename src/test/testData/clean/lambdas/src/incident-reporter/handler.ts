// The incident reporter opens a ticket in the archive's tracker and pages the duty officer for high severity.
// Invented for the Aslan demo project.
import type { IncidentReport, IncidentTicket } from '../shared/contracts';

/** The tracker's and the pager's clients, provided by the archive's runtime layer. */
declare const tracker: { open(key: string, title: string, details: unknown): Promise<string> };
declare const pager: { page(message: string, ticketId: string): Promise<void> };

export async function handler(report: IncidentReport): Promise<IncidentTicket> {
  const ticketId = await tracker.open(report.incidentKey, report.summary, report);
  const paged = report.severity === 'high';
  if (paged) await pager.page(`Intake stopped: ${report.summary}`, ticketId);
  return { ticketId, paged };
}
