/**
 * Pipeline Overview Dashboard – TV-optimised client-side rendering.
 *
 * Fetches aggregated pipeline data from the view's /data endpoint and
 * renders a dark-themed dashboard designed for information radiators.
 * Supports kiosk mode (?kiosk), auto-refresh, sparklines, and trend arrows.
 */
(function () {
    "use strict";

    var dashboard = document.getElementById("overview-dashboard");
    if (!dashboard) return;

    var viewUrl = dashboard.getAttribute("data-view-url") || "";
    if (viewUrl && viewUrl.charAt(viewUrl.length - 1) !== "/") viewUrl += "/";

    var config = {
        viewUrl: viewUrl,
        refreshInterval: parseInt(dashboard.getAttribute("data-refresh-interval"), 10) || 30,
        title: dashboard.getAttribute("data-dashboard-title") || "Pipeline Overview"
    };

    var state = {
        data: null,
        lastError: null,
        refreshTimer: null,
        clockTimer: null
    };

    /* ================================================================
       Kiosk mode
       ================================================================ */

    var isKiosk = window.location.search.indexOf("kiosk") !== -1;
    if (isKiosk) document.body.classList.add("od-kiosk");

    /* ================================================================
       Data fetching
       ================================================================ */

    function fetchData() {
        var xhr = new XMLHttpRequest();
        xhr.open("GET", config.viewUrl + "data", true);
        xhr.setRequestHeader("Accept", "application/json");
        xhr.timeout = 30000;

        xhr.onreadystatechange = function () {
            if (xhr.readyState !== 4) return;
            if (xhr.status === 200) {
                try {
                    state.data = JSON.parse(xhr.responseText);
                    state.lastError = null;
                    render();
                } catch (e) {
                    showError("Failed to parse server response.");
                }
            } else if (xhr.status === 403) {
                showError("Access denied.");
            } else {
                showError("Failed to fetch data (HTTP " + xhr.status + ").");
            }
        };

        xhr.ontimeout = function () {
            showError("Request timed out.");
        };

        xhr.send();
    }

    /* ================================================================
       Main render
       ================================================================ */

    function render() {
        if (!state.data) return;
        var d = state.data;
        var s = d.summary || {};

        var html = "";
        html += renderHeader(d);
        html += renderStatusBanner(s);
        html += renderKPIs(s);
        html += renderHealthGrid(d.pipelines || []);

        html += '<div class="od-bottom">';
        html += renderFailures(d.currentFailures || []);
        html += renderTrends(s);
        html += "</div>";

        html += '<div class="od-bottom">';
        html += renderSlowestStages(d.slowestStages || []);
        html += renderStalePipelines(d.stalePipelines || []);
        html += "</div>";

        dashboard.innerHTML = html;
        bindEvents();
    }

    /* ================================================================
       Header
       ================================================================ */

    function renderHeader(d) {
        var h = '<div class="od-header">';
        h += '<div class="od-header-left">';
        h += '<span class="od-title">' + esc(d.viewName || config.title) + "</span>";
        if (d.headerMessage) {
            h += '<span class="od-header-message">' + esc(d.headerMessage) + "</span>";
        }
        h += "</div>";
        h += '<div class="od-header-right">';
        h += '<span class="od-heartbeat"></span>';
        h += '<span id="od-clock">' + formatClock() + "</span>";
        h += '<button id="od-kiosk-btn" class="od-kiosk-btn">'
           + (isKiosk ? "Exit Kiosk" : "Kiosk") + "</button>";
        h += "</div></div>";
        return h;
    }

    /* ================================================================
       Status banner
       ================================================================ */

    function renderStatusBanner(s) {
        if (s.failingPipelines > 0) {
            return '<div class="od-status-banner od-status-failing">'
                + s.failingPipelines + " pipeline"
                + (s.failingPipelines > 1 ? "s" : "") + " failing</div>";
        }
        if (s.unstablePipelines > 0) {
            return '<div class="od-status-banner od-status-unstable">'
                + s.unstablePipelines + " pipeline"
                + (s.unstablePipelines > 1 ? "s" : "") + " unstable</div>";
        }
        return '<div class="od-status-banner od-status-healthy">'
            + "\u2714 All pipelines healthy</div>";
    }

    /* ================================================================
       KPI cards
       ================================================================ */

    function renderKPIs(s) {
        var h = '<div class="od-kpis">';

        h += kpiCard(formatPct(s.overallSuccessRate), "Success Rate",
            s.overallSuccessRate >= 90 ? "success" : (s.overallSuccessRate >= 70 ? "warning" : "failure"));
        h += kpiCard(s.buildsToday, "Builds Today", "info");
        h += kpiCard(s.buildsThisWeek, "Builds This Week", "neutral");
        h += kpiCard(formatDuration(s.avgBuildDurationMs), "Avg Build Time", "neutral");
        h += kpiCard(s.failingPipelines, "Failing", s.failingPipelines > 0 ? "failure" : "success");
        h += kpiCard(s.queueSize, "Queued", s.queueSize > 2 ? "warning" : "neutral");

        h += "</div>";
        return h;
    }

    function kpiCard(value, label, type) {
        return '<div class="od-kpi od-kpi-' + type + '">'
            + '<div class="od-kpi-value">' + value + "</div>"
            + '<div class="od-kpi-label">' + label + "</div>"
            + "</div>";
    }

    /* ================================================================
       Health grid
       ================================================================ */

    function renderHealthGrid(pipelines) {
        if (pipelines.length === 0) {
            return '<div class="od-section"><div class="od-empty">'
                + "No pipelines configured.</div></div>";
        }

        /* group by groupName, preserving order */
        var groupMap = {};
        var groupOrder = [];
        pipelines.forEach(function (p) {
            var g = p.groupName || "Ungrouped";
            if (!groupMap[g]) {
                groupMap[g] = [];
                groupOrder.push(g);
            }
            groupMap[g].push(p);
        });

        var h = '<div class="od-section">';
        h += '<div class="od-section-title">Pipeline Health</div>';

        groupOrder.forEach(function (gn) {
            if (groupOrder.length > 1) {
                h += '<div class="od-group-label">' + esc(gn) + "</div>";
            }
            h += '<div class="od-health-grid">';
            groupMap[gn].forEach(function (p) {
                h += renderHealthCell(p);
            });
            h += "</div>";
        });

        h += "</div>";
        return h;
    }

    function renderHealthCell(p) {
        /* skip placeholder statuses */
        if (p.lastStatus === "NOT_FOUND" || p.lastStatus === "NOT_A_PIPELINE") return "";

        var colorClass = healthColor(p);
        var rate = p.lastStatus === "NO_BUILDS" ? "-"
            : (p.lastStatus === "BUILDING" && p.totalBuilds7d === 0) ? "-"
            : formatPct(p.successRate7d);

        var trendArrow = trendIcon(p.successTrend);

        var h = '<div class="od-health-cell ' + colorClass + '">';
        h += '<div class="od-health-cell-name" title="' + esc(p.jobName) + '">'
           + esc(p.displayName || p.jobName) + "</div>";
        h += '<div class="od-health-cell-rate">' + rate + "</div>";

        if (p.lastStatus !== "NO_BUILDS") {
            h += '<div class="od-health-cell-meta">'
               + p.totalBuilds7d + " builds / 7d"
               + (p.avgDurationMs > 0 ? " \u00B7 " + formatDuration(p.avgDurationMs) : "")
               + "</div>";
            h += '<div class="od-health-cell-trend">' + trendArrow + "</div>";

            /* mini sparkline */
            var sparkSvg = sparkline(p.dailyBuilds || [], 100, 24, "sparkline-" + esc(p.jobName),
                p.lastStatus === "FAILURE" ? "var(--od-failure)" : "var(--od-info)");
            if (sparkSvg) {
                h += '<div class="od-health-cell-sparkline">' + sparkSvg + "</div>";
            }
        }

        h += "</div>";
        return h;
    }

    function healthColor(p) {
        if (p.lastStatus === "BUILDING") return "od-hc-building";
        if (p.lastStatus === "NO_BUILDS") return "od-hc-gray";
        if (p.lastStatus === "FAILURE") return "od-hc-red";
        if (p.lastStatus === "UNSTABLE") return "od-hc-yellow";
        if (p.successRate7d >= 90) return "od-hc-green";
        if (p.successRate7d >= 70) return "od-hc-yellow";
        return "od-hc-red";
    }

    /* ================================================================
       Current failures
       ================================================================ */

    function renderFailures(failures) {
        var h = '<div class="od-card">';
        h += '<div class="od-section-title">Current Failures</div>';

        if (failures.length === 0) {
            h += '<div class="od-all-healthy">'
               + '<span class="od-all-healthy-icon">\u2714</span>'
               + " All pipelines healthy</div>";
        } else {
            failures.forEach(function (f) {
                h += '<div class="od-failure-item">';
                h += '<div class="od-failure-dot"></div>';
                h += '<div>';
                h += '<div class="od-failure-name">'
                   + '<a href="' + esc(f.buildUrl) + '">'
                   + esc(f.displayName || f.jobName) + "</a></div>";
                h += '<div class="od-failure-detail">';
                if (f.failedStage) h += "Stage: " + esc(f.failedStage) + " \u00B7 ";
                h += "Failed " + formatTimeAgo(f.failedSinceMs);
                h += "</div></div>";
                h += '<div class="od-failure-badge">'
                   + f.consecutiveFailures + "x</div>';
                h += "</div>";
            });
        }

        h += "</div>";
        return h;
    }

    /* ================================================================
       Trends
       ================================================================ */

    function renderTrends(s) {
        var h = '<div class="od-card">';
        h += '<div class="od-section-title">Trends (14 days)</div>';

        /* build velocity sparkline */
        h += '<div class="od-trend-row">';
        h += '<span class="od-trend-label">Build Volume</span>';
        h += '<div class="od-trend-sparkline">'
           + sparkline(s.dailyBuilds || [], 200, 36, "agg-builds", "var(--od-info)")
           + "</div>";
        var total14 = sum(s.dailyBuilds || []);
        h += '<span class="od-trend-value">' + total14 + "</span>";
        h += "</div>";

        /* success rate sparkline */
        h += '<div class="od-trend-row">';
        h += '<span class="od-trend-label">Success Rate</span>';
        h += '<div class="od-trend-sparkline">'
           + sparkline(s.dailySuccessRate || [], 200, 36, "agg-success", "var(--od-success)")
           + "</div>";
        var avgSr = avg(s.dailySuccessRate || []);
        h += '<span class="od-trend-value">' + formatPct(avgSr) + "</span>";
        h += "</div>";

        h += "</div>";
        return h;
    }

    /* ================================================================
       Slowest stages
       ================================================================ */

    function renderSlowestStages(stages) {
        var h = '<div class="od-card">';
        h += '<div class="od-section-title">Slowest Stages</div>';

        if (stages.length === 0) {
            h += '<div class="od-empty">No stage data available.</div>';
        } else {
            var maxDur = stages[0].avgDurationMs || 1;
            stages.forEach(function (s, i) {
                var pct = Math.round((s.avgDurationMs / maxDur) * 100);
                h += '<div class="od-stage-item">';
                h += '<span class="od-stage-rank">#' + (i + 1) + "</span>";
                h += '<div class="od-stage-info">';
                h += '<div class="od-stage-name">' + esc(s.stageName) + "</div>";
                h += '<div class="od-stage-job">' + esc(s.displayName) + "</div>";
                h += '<div class="od-stage-bar-bg"><div class="od-stage-bar" style="width:'
                   + pct + '%"></div></div>';
                h += "</div>";
                h += '<span class="od-stage-duration">'
                   + formatDuration(s.avgDurationMs) + "</span>";
                h += "</div>";
            });
        }

        h += "</div>";
        return h;
    }

    /* ================================================================
       Stale pipelines
       ================================================================ */

    function renderStalePipelines(stale) {
        var h = '<div class="od-card">';
        h += '<div class="od-section-title">Stale Pipelines</div>';

        if (stale.length === 0) {
            h += '<div class="od-empty">All pipelines have recent builds.</div>';
        } else {
            stale.forEach(function (s) {
                h += '<div class="od-stale-item">';
                h += '<span class="od-stale-name">'
                   + esc(s.displayName || s.jobName) + "</span>";
                h += '<span class="od-stale-ago">'
                   + s.daysSinceLastBuild + "d ago</span>";
                h += "</div>";
            });
        }

        h += "</div>";
        return h;
    }

    /* ================================================================
       Sparkline SVG generator
       ================================================================ */

    function sparkline(data, width, height, id, color) {
        if (!data || data.length < 2) return "";

        var max = 0;
        for (var i = 0; i < data.length; i++) {
            if (data[i] > max) max = data[i];
        }
        if (max === 0) max = 1;

        var pad = 2;
        var w = width - pad * 2;
        var ht = height - pad * 2;
        var step = w / (data.length - 1);
        var points = [];
        for (var j = 0; j < data.length; j++) {
            var x = pad + j * step;
            var y = pad + ht - (data[j] / max * ht);
            points.push(x.toFixed(1) + "," + y.toFixed(1));
        }

        var gradId = "g-" + id.replace(/[^a-zA-Z0-9]/g, "");
        var svg = '<svg width="' + width + '" height="' + height
            + '" viewBox="0 0 ' + width + " " + height + '">';
        svg += "<defs>"
            + '<linearGradient id="' + gradId + '" x1="0" y1="0" x2="0" y2="1">'
            + '<stop offset="0%" stop-color="' + color + '" stop-opacity="0.25"/>'
            + '<stop offset="100%" stop-color="' + color + '" stop-opacity="0.02"/>'
            + "</linearGradient></defs>";

        /* filled area */
        var areaPoints = pad + "," + (pad + ht) + " "
            + points.join(" ") + " "
            + (pad + w) + "," + (pad + ht);
        svg += '<polygon points="' + areaPoints + '" fill="url(#' + gradId + ')"/>';

        /* line */
        svg += '<polyline points="' + points.join(" ")
            + '" fill="none" stroke="' + color + '" stroke-width="1.5" stroke-linecap="round"/>';

        /* dot on last point */
        var lastParts = points[points.length - 1].split(",");
        svg += '<circle cx="' + lastParts[0] + '" cy="' + lastParts[1]
            + '" r="2.5" fill="' + color + '"/>';

        svg += "</svg>";
        return svg;
    }

    /* ================================================================
       Formatting helpers
       ================================================================ */

    function formatDuration(ms) {
        if (ms == null || ms < 0) return "-";
        if (ms < 1000) return ms + "ms";
        var sec = Math.floor(ms / 1000);
        if (sec < 60) return sec + "s";
        var min = Math.floor(sec / 60);
        sec = sec % 60;
        if (min < 60) return min + "m " + sec + "s";
        var hr = Math.floor(min / 60);
        min = min % 60;
        return hr + "h " + min + "m";
    }

    function formatTimeAgo(ts) {
        if (!ts) return "";
        var diff = Date.now() - ts;
        if (diff < 0) return "just now";
        var sec = Math.floor(diff / 1000);
        if (sec < 60) return sec + "s ago";
        var min = Math.floor(sec / 60);
        if (min < 60) return min + "m ago";
        var hr = Math.floor(min / 60);
        if (hr < 24) return hr + "h ago";
        var d = Math.floor(hr / 24);
        return d + "d ago";
    }

    function formatPct(v) {
        if (v == null) return "-";
        return Math.round(v * 10) / 10 + "%";
    }

    function formatClock() {
        var d = new Date();
        var hh = pad2(d.getHours());
        var mm = pad2(d.getMinutes());
        var ss = pad2(d.getSeconds());
        var months = ["Jan","Feb","Mar","Apr","May","Jun",
                      "Jul","Aug","Sep","Oct","Nov","Dec"];
        return hh + ":" + mm + ":" + ss + "  \u00B7  "
            + d.getDate() + " " + months[d.getMonth()] + " " + d.getFullYear();
    }

    function pad2(n) { return n < 10 ? "0" + n : "" + n; }

    function trendIcon(trend) {
        if (trend === "IMPROVING") return '<span class="od-trend-improving">\u2191</span>';
        if (trend === "DEGRADING") return '<span class="od-trend-degrading">\u2193</span>';
        return '<span class="od-trend-stable">\u2192</span>';
    }

    function esc(s) {
        if (!s) return "";
        var el = document.createElement("span");
        el.appendChild(document.createTextNode(s));
        return el.innerHTML;
    }

    function sum(arr) {
        var t = 0;
        for (var i = 0; i < arr.length; i++) t += arr[i];
        return t;
    }

    function avg(arr) {
        if (!arr || arr.length === 0) return 0;
        return sum(arr) / arr.length;
    }

    /* ================================================================
       Error display
       ================================================================ */

    function showError(msg) {
        state.lastError = msg;
        if (!state.data) {
            dashboard.innerHTML = '<div class="od-error">' + esc(msg) + "</div>";
        }
    }

    /* ================================================================
       Event binding
       ================================================================ */

    function bindEvents() {
        var kioskBtn = document.getElementById("od-kiosk-btn");
        if (kioskBtn) {
            kioskBtn.addEventListener("click", function () {
                if (isKiosk) {
                    /* remove kiosk param and reload */
                    var url = window.location.href.replace(/[?&]kiosk[^&]*/g, "");
                    if (url.indexOf("?") === -1 && url.indexOf("&") > -1) {
                        url = url.replace("&", "?");
                    }
                    window.location.href = url;
                } else {
                    var sep = window.location.href.indexOf("?") === -1 ? "?" : "&";
                    window.location.href = window.location.href + sep + "kiosk";
                }
            });
        }
    }

    /* ================================================================
       Clock updater
       ================================================================ */

    function updateClock() {
        var el = document.getElementById("od-clock");
        if (el) el.textContent = formatClock();
    }

    /* ================================================================
       Bootstrap
       ================================================================ */

    fetchData();

    if (config.refreshInterval > 0) {
        state.refreshTimer = setInterval(fetchData, config.refreshInterval * 1000);
    }
    state.clockTimer = setInterval(updateClock, 1000);

    window.addEventListener("beforeunload", function () {
        if (state.refreshTimer) clearInterval(state.refreshTimer);
        if (state.clockTimer) clearInterval(state.clockTimer);
    });
})();
