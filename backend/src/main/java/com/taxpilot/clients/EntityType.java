package com.taxpilot.clients;

import java.util.Set;

/** Legal form of a client, with the PAN holder-type characters (4th PAN character) each form may carry. */
public enum EntityType {
    INDIVIDUAL("Individual", Set.of('P')),
    HUF("Hindu Undivided Family", Set.of('H')),
    PROPRIETORSHIP("Sole Proprietorship", Set.of('P')),
    PARTNERSHIP_FIRM("Partnership Firm", Set.of('F')),
    LLP("Limited Liability Partnership", Set.of('F')),
    PRIVATE_LIMITED("Private Limited Company", Set.of('C')),
    PUBLIC_LIMITED("Public Limited Company", Set.of('C')),
    ONE_PERSON_COMPANY("One Person Company", Set.of('C')),
    TRUST("Trust", Set.of('T')),
    SOCIETY("Society", Set.of('A', 'T')),
    AOP("Association of Persons", Set.of('A')),
    BOI("Body of Individuals", Set.of('B')),
    LOCAL_AUTHORITY("Local Authority", Set.of('L')),
    ARTIFICIAL_JURIDICAL_PERSON("Artificial Juridical Person", Set.of('J')),
    GOVERNMENT("Government", Set.of('G'));

    private final String label;
    private final Set<Character> panHolderTypes;

    EntityType(String label, Set<Character> panHolderTypes) {
        this.label = label;
        this.panHolderTypes = panHolderTypes;
    }

    public String label() {
        return label;
    }

    public boolean acceptsPanHolderType(char c) {
        return panHolderTypes.contains(c);
    }

    public Set<Character> panHolderTypes() {
        return panHolderTypes;
    }
}
