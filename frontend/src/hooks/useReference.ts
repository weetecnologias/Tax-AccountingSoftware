import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { EntityTypeOption, Option, UserRef } from "@/types/api";

const forever = { staleTime: Infinity, gcTime: Infinity } as const;

export const useStates = () => useQuery({ queryKey: ["ref", "states"], queryFn: () => api.get<Option[]>("/reference/states"), ...forever });

export const useEntityTypes = () =>
  useQuery({ queryKey: ["ref", "entity-types"], queryFn: () => api.get<EntityTypeOption[]>("/reference/entity-types"), ...forever });

export const useDocumentTypes = () =>
  useQuery({ queryKey: ["ref", "document-types"], queryFn: () => api.get<Option[]>("/reference/document-types"), ...forever });

export const useAssignableUsers = (permission?: string, enabled = true) =>
  useQuery({
    queryKey: ["users", "assignable", permission ?? "any"],
    queryFn: () => api.get<UserRef[]>(`/users/assignable${permission ? `?permission=${permission}` : ""}`),
    enabled,
  });

export function labelOf(options: Option[] | undefined, value: string | null | undefined): string {
  if (!value) return "—";
  return options?.find((o) => o.value === value)?.label ?? value;
}
