function chosenSource() {
  try {
    const asked = new URLSearchParams(location.search).get("api");
    if (asked === "mock" || asked === "live") sessionStorage.setItem("vault.api", asked);
    return sessionStorage.getItem("vault.api");
  } catch {
    return null;
  }
}

export const config = {
  productName: "Vault",
  projectTitle: "Policy-Based Secure Cloud File Storage",
  apiSource: chosenSource() ?? "live",
  apiBase: "/api",
  maxUploadBytes: 2 * 1024 ** 3,
  levelLabels: {
    owner: "Owner",
    editor: "Editor",
    viewer: "Viewer",
    guest: "Guest",
  },
  tierLabels: {
    original: "Original",
    reduced: "Reduced",
    lowest: "Lowest / first page",
    metadata: "Metadata only",
  },
};
