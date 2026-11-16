import React from "react";
import { createRoot } from "react-dom/client";
import "leaflet/dist/leaflet.css";
import "wanted-sans/fonts/webfonts/variable/split/WantedSansVariable.css";
import "./styles/global.css";
import "./styles/theme.css";
import App from "./App.jsx";
import { I18nProvider } from "./i18n/I18nProvider.jsx";

/**
 * React application entry point.
 */
createRoot(document.getElementById("root")).render(
  <React.StrictMode>
    <I18nProvider>
      <App />
    </I18nProvider>
  </React.StrictMode>
);
