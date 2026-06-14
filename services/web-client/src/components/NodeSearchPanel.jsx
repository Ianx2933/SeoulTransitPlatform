import { useState } from "react";
import { fetchNodeSearch } from "../api/mapDemandApi.js";

/** Search panel for node-centered analysis. */
export default function NodeSearchPanel({ onSelectNode }) {
  const [keyword, setKeyword] = useState("");
  const [results, setResults] = useState([]);
  const [loading, setLoading] = useState(false);
  const [errorMessage, setErrorMessage] = useState("");

  /** Calls the node search API. */
  const handleSearch = async (event) => {
    event.preventDefault();
    const trimmedKeyword = keyword.trim();
    if (trimmedKeyword.length < 2) { setErrorMessage("Enter at least 2 characters."); setResults([]); return; }
    setLoading(true); setErrorMessage("");
    try { setResults(await fetchNodeSearch({ keyword: trimmedKeyword, limit: 30 })); }
    catch (error) { setErrorMessage(error.message); setResults([]); }
    finally { setLoading(false); }
  };

  /** Sends selected node to parent. */
  const handleSelectNode = (node) => onSelectNode?.({ ...node, source: "node-search" });

  return (
    <section className="node-search-panel" style={{ marginTop: "12px", padding: "12px", border: "1px solid #dddddd", borderRadius: "8px", backgroundColor: "#fafafa" }}>
      <form onSubmit={handleSearch} style={{ display: "flex", gap: "8px", alignItems: "center", flexWrap: "wrap" }}>
        <strong>Stop / station search</strong>
        <label>Keyword <input type="text" value={keyword} onChange={(event) => setKeyword(event.target.value)} placeholder="e.g. 강남, 서현, 07616" style={{ marginLeft: "8px", padding: "6px 8px" }} /></label>
        <button type="submit" disabled={loading}>{loading ? "Searching..." : "Search node"}</button>
        <span style={{ color: "#555555" }}>Open node analysis without loading a route first.</span>
      </form>
      {errorMessage && <p style={{ margin: "8px 0 0", color: "#b00020" }}>{errorMessage}</p>}
      {results.length > 0 && (
        <div style={{ marginTop: "10px" }}>
          <strong>Search results</strong>
          <ol style={{ maxHeight: "160px", overflowY: "auto", margin: "6px 0 0", paddingRight: "12px" }}>
            {results.map((node) => (
              <li key={`${node.mode}-${node.nodeId}-${node.lat}-${node.lng}`}>
                <button type="button" onClick={() => handleSelectNode(node)} style={{ border: "1px solid #cccccc", borderRadius: "6px", padding: "4px 8px", backgroundColor: "#ffffff", cursor: "pointer", textAlign: "left" }}>
                  [{node.mode}] {node.nodeName} / {node.nodeId}
                </button>
              </li>
            ))}
          </ol>
        </div>
      )}
    </section>
  );
}
