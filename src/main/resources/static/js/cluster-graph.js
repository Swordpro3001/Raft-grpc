// Draws the data in #graph-data with Cytoscape.js. The server decides what the graph shows.
(function () {
    const NODE_SIZE = 56;
    let cy = null;
    let layoutKey = '';

    function color(name) {
        return getComputedStyle(document.documentElement).getPropertyValue('--color-' + name).trim();
    }

    // What the node's monogram shows: the number of "node3", or else the first letter
    function monogram(id) {
        const number = id.match(/\d+$/);
        return number ? number[0] : id.replace(/[^a-zA-Z0-9]/g, '').charAt(0).toUpperCase();
    }

    // A monogram with the same status dot as in the tables, placed on the edge of the circle.
    // Drawn much larger than shown, because the canvas scales a bitmap of it down.
    function avatar(id, status, palette) {
        const dot = status.fill
            ? `<circle cx="47" cy="47" r="6" fill="${status.fill}" stroke="${palette.surface}" stroke-width="2.5"/>`
            : `<circle cx="47" cy="47" r="6" fill="${palette.surface}" stroke="${palette.surface}" stroke-width="2.5"/>`
              + `<circle cx="47" cy="47" r="4.5" fill="none" stroke="${palette.muted}" stroke-width="2"/>`;
        const text = `<text x="28" y="28" text-anchor="middle" dominant-baseline="central" fill="${status.ink}"`
            + ` font-family="${palette.font}" font-size="19" font-weight="600">${monogram(id)}</text>`;
        return 'data:image/svg+xml,' + encodeURIComponent(
            `<svg xmlns="http://www.w3.org/2000/svg" width="224" height="224" viewBox="0 0 56 56">${text}${dot}</svg>`);
    }

    function stylesheet() {
        const ink = color('ink');
        const muted = color('muted');
        const line = color('line-solid');
        const surface = color('surface');
        const accent = color('accent');
        const font = getComputedStyle(document.body).fontFamily;
        const palette = { surface, muted, font: font.replace(/"/g, "'") };

        const statuses = {
            LEADER: { fill: color('good'), ink },
            FOLLOWER: { ink },
            CANDIDATE: { fill: color('warn'), ink },
            SUSPENDED: { fill: color('warn'), ink },
            STARTING: { fill: color('warn'), ink },
            OFFLINE: { fill: color('bad'), ink: muted },
            NOT_A_MEMBER: { ink: muted }
        };

        return [
            {
                selector: 'node',
                style: {
                    shape: 'ellipse', width: NODE_SIZE, height: NODE_SIZE,
                    'background-color': color('node'), 'border-width': 1, 'border-color': line,
                    'background-image': element => avatar(element.id(), statuses[element.data('role')] ?? statuses.FOLLOWER, palette),
                    'background-fit': 'contain', 'background-clip': 'none', 'background-image-containment': 'over',
                    label: 'data(label)', color: ink, 'font-size': 12, 'font-family': font,
                    'text-wrap': 'wrap', 'text-valign': 'bottom', 'text-halign': 'center', 'text-margin-y': 8, 'line-height': 1.4,
                    // Links can pass behind a label, and must not run through its text
                    'text-background-color': surface, 'text-background-opacity': 1, 'text-background-padding': 2, 'text-background-shape': 'round-rectangle'
                }
            },
            { selector: 'node[role = "LEADER"]', style: { 'border-color': ink, 'border-width': 1.5 } },
            { selector: 'node[role = "SUSPENDED"], node[role = "STARTING"], node[role = "OFFLINE"]', style: { 'border-style': 'dashed' } },
            { selector: 'node[role = "NOT_A_MEMBER"]', style: { 'border-style': 'dotted' } },
            { selector: 'node[role = "OFFLINE"], node[role = "NOT_A_MEMBER"]', style: { color: muted } },
            {
                selector: 'node[?selected]',
                style: {
                    'border-color': accent, 'border-width': 2,
                    'underlay-color': accent, 'underlay-opacity': 0.14, 'underlay-padding': 6, 'underlay-shape': 'ellipse'
                }
            },
            {
                selector: 'edge',
                style: {
                    'curve-style': 'unbundled-bezier', 'control-point-distances': 22, 'control-point-weights': 0.5,
                    width: 1.5, 'line-cap': 'round', 'line-color': line,
                    'source-distance-from-node': 5, 'target-distance-from-node': 5,
                    label: 'data(label)', color: ink, 'font-size': 11, 'text-background-color': surface,
                    'text-background-opacity': 1, 'text-background-padding': 3, 'text-background-shape': 'round-rectangle'
                }
            },
            { selector: 'edge[state = "behind"]', style: { 'line-color': color('warn'), width: 2 } },
            { selector: 'edge[state = "unreachable"]', style: { 'line-style': 'dashed', 'line-color': color('bad') } }
        ];
    }

    // Machines stay at the same place on an ellipse, so that only their states change on screen
    function placeNodes() {
        const nodes = cy.nodes().sort((a, b) => a.id().localeCompare(b.id(), undefined, { numeric: true }));
        const { w, h } = { w: cy.width(), h: cy.height() };
        nodes.forEach((node, i) => {
            const angle = -Math.PI / 2 + 2 * Math.PI * i / nodes.length;
            node.position({ x: Math.cos(angle) * Math.max(w * 0.36, 220), y: Math.sin(angle) * Math.max(h * 0.36, 110) });
        });
        cy.fit(undefined, 24);
    }

    function select(event) {
        document.querySelector(`[data-node-link="${event.target.id()}"]`)?.click();
    }

    function create(container) {
        cy = cytoscape({
            container,
            style: stylesheet(),
            maxZoom: 1,
            userZoomingEnabled: false,
            userPanningEnabled: false,
            boxSelectionEnabled: false,
            autoungrabify: true,
            autounselectify: true
        });
        cy.on('tap', 'node', select);
        cy.on('mouseover', 'node', () => { container.style.cursor = 'pointer'; });
        cy.on('mouseout', 'node', () => { container.style.cursor = ''; });
        layoutKey = '';
    }

    function sync() {
        const container = document.getElementById('cluster-graph');
        const source = document.getElementById('graph-data');
        if (!container || !source) return;
        if (!cy || cy.container() !== container) create(container);

        const { nodes, edges } = JSON.parse(source.dataset.graph);
        const wanted = new Set([...nodes, ...edges].map(element => element.id));
        cy.batch(() => {
            cy.elements().filter(element => !wanted.has(element.id())).remove();
            nodes.forEach(data => merge('nodes', data));
            edges.forEach(data => merge('edges', data));
        });

        const key = nodes.map(node => node.id).join();
        if (key !== layoutKey) {
            layoutKey = key;
            placeNodes();
        }
    }

    function merge(group, data) {
        const element = cy.getElementById(data.id);
        if (element.nonempty()) element.data(data);
        else cy.add({ group, data });
    }

    function setDisconnected(disconnected) {
        document.body.classList.toggle('disconnected', disconnected);
    }

    document.addEventListener('DOMContentLoaded', sync);
    document.addEventListener('htmx:afterSettle', sync);
    document.addEventListener('htmx:sendError', () => setDisconnected(true));
    document.addEventListener('htmx:afterOnLoad', () => setDisconnected(false));
    window.addEventListener('resize', () => { if (cy) placeNodes(); });
    document.addEventListener('themechange', () => { if (cy) cy.style(stylesheet()); });
})();
