import { apiRequest } from "./api";
import { authGateway } from "./auth";
import type {
  AgreeProposalResult,
  CustomerProposal,
  FmRentalOrder,
  ProposalSnapshot,
  ProposalStatus,
  RejectProposalResult,
} from "./types";

function accessToken(): string | undefined {
  return authGateway.getSession()?.token;
}

export function fetchMyProposals(status?: ProposalStatus): Promise<CustomerProposal[]> {
  const query = status ? `?status=${status}` : "";
  return apiRequest<CustomerProposal[]>(`/api/proposals${query}`, {
    accessToken: accessToken(),
  });
}

export function agreeProposal(proposalId: number): Promise<AgreeProposalResult> {
  return apiRequest<AgreeProposalResult>(`/api/proposals/${proposalId}/agree`, {
    method: "POST",
    accessToken: accessToken(),
  });
}

export function rejectProposal(proposalId: number, note: string): Promise<RejectProposalResult> {
  return apiRequest<RejectProposalResult>(`/api/proposals/${proposalId}/reject`, {
    method: "POST",
    body: { note },
    accessToken: accessToken(),
  });
}

export function fetchRentalOrder(orderId: number): Promise<FmRentalOrder> {
  return apiRequest<FmRentalOrder>(`/api/fm/rental-orders/${orderId}`, {
    accessToken: accessToken(),
  });
}

export function reproposeUnit(
  orderId: number,
  unitId: number,
  overrideReason?: string,
): Promise<ProposalSnapshot> {
  return apiRequest<ProposalSnapshot>(`/api/rental-orders/${orderId}/proposals`, {
    method: "POST",
    body: { unitId, overrideReason: overrideReason || undefined },
    accessToken: accessToken(),
  });
}
