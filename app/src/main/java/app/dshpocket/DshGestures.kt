package app.dshpocket

/**
 * Touch gestures the harness runs inside the WebView.
 *
 * The harness is a web app with no touch gesture support of its own, and its two panels animate in
 * completely different ways, so both are driven from the page: a native implementation would have to
 * cross the JavaScript bridge on every frame.
 *
 * Injected only by this shell, so the harness in an ordinary browser is unaffected.
 *
 * What the script does:
 *
 * - drags the left rail open from the right swipe and the right panel in from the left swipe, moving
 *   the thing with the finger and settling it on release
 * - clicks the harness's own control once the settle finishes, so its React state matches what the
 *   user sees and the next tap behaves normally
 * - pinches an opened image to scale it, drags it with one finger while scaled, and returns it to fit
 *   on a double tap
 *
 * The harness's relevant elements, found by inspecting the running app. Everything here is a `data-`
 * attribute or an accessible name rather than a CSS-modules class name, because those names carry a
 * build hash and change on every release:
 *
 * | Element | Selector |
 * | --- | --- |
 * | Left rail | `[class*="sidebarCol"]`; its parent is the animating grid |
 * | Left rail control | the button whose `aria-label` names the sidebar |
 * | Right panel | `[data-sidebar-right-panel]` |
 * | Right panel open | `[data-sidebar-right-expand]` |
 * | Right panel close | `[data-sidebar-right-toggle]` |
 * | Opened image | `[data-image-preview] img` |
 *
 * Three findings shape the code, and each cost a round of debugging to learn:
 *
 * The left rail does not move by transform. The shell is a CSS grid and the rail is its first column,
 * so the harness animates `grid-template-columns` on the frame. Driving that means reading and
 * rewriting the column list, and the width it opens to is taken from the harness's own layout rather
 * than assumed, because the rail is narrow when collapsed and wide when open.
 *
 * A collapsed right panel is `visibility: hidden`, so a drag that only sets a transform paints
 * nothing and the panel appears fully open in one step. The panel and any hidden ancestors are forced
 * visible for the duration of the drag and handed back when it settles.
 *
 * A two finger touch starts life as a one finger touch, and would otherwise be read as a drawer drag
 * before the second finger arrives. A pinch therefore cancels any drag in progress, and while a pinch
 * is active the drawer gesture stands down.
 */
internal object DshGestures {

    /**
     * The script source.
     *
     * Written without `$` anywhere, because Kotlin would read it as a template expression inside a
     * raw string. It is also self-guarding: injecting it twice is a no-op.
     */
    val script: String = """
        (function () {
          if (window.__dshPocketGestures) { return; }
          window.__dshPocketGestures = true;

          var SIDEBAR_RAIL = '[class*="sidebarCol"]';
          var RIGHT_PANEL = '[data-sidebar-right-panel]';
          var RIGHT_EXPAND = '[data-sidebar-right-expand]';
          var RIGHT_TOGGLE = '[data-sidebar-right-toggle]';
          var IMAGE_FRAME = '[data-image-preview]';

          // How far a panel must travel, as a share of the distance it can move, before the gesture
          // opens instead of closing. Kept low so a short swipe is enough and the settle animation
          // still has most of the distance left to travel.
          var COMMIT_FRACTION = 0.2;
          // Movement before the gesture commits to an axis, so a vertical scroll is never hijacked.
          var AXIS_SLOP = 12;
          // A quick throw commits in the direction it was thrown, however short it was.
          var FLICK_VELOCITY = 0.25;
          var FLICK_WINDOW_MS = 100;
          // Shortest window that may be used to judge a throw.
          var MIN_VELOCITY_SPAN_MS = 12;
          // The harness animates both panels over --ds-transition-duration-slow.
          var SETTLE_MS = 300;
          // Time allowed for React to commit the toggled state before the inline override is dropped.
          var HANDOVER_MS = 80;
          // Widths the harness uses for the rail, as a fallback when the grid cannot be read.
          var RAIL_COLLAPSED = 56;
          var RAIL_OPEN = 280;
          // A rail wider than this counts as open.
          var RAIL_OPEN_THRESHOLD = 120;
          // How far the rail's swipe must travel before it is taken as deliberate.
          var SIDEBAR_COMMIT_PX = 60;
          // How often the rail state is re-read, as a fallback for a frame the observer missed.
          var RAIL_STATE_POLL_MS = 400;
          // The hidden input the composer attaches files through, and the button that opens it.
          //
          // The button is found as the input's previous sibling, not by its label: the shell
          // translates that label ("Add attachment" in English, "添加附件" in Chinese), so matching
          // the text finds nothing in any other language and the button is silently never added.
          // The input carries no accept attribute either, so an image request is marked on it
          // before it is clicked.
          var FILE_INPUT = 'input[type="file"]';
          var ADD_IMAGE_ID = 'dsh-add-image';
          var ADD_IMAGE_LABEL = 'Add image';
          // How often the button is re-checked, because the shell rebuilds its toolbar on
          // navigation.
          var IMAGE_BUTTON_POLL_MS = 700;
          var ADD_IMAGE_ICON = '<svg viewBox="0 0 16 16" width="15" height="15" fill="none"'
            + ' stroke="currentColor" stroke-width="1.3" stroke-linecap="round"'
            + ' stroke-linejoin="round"><rect x="1.6" y="2.6" width="12.8" height="10.8" rx="2"/>'
            + '<circle cx="5.6" cy="6.4" r="1.15"/>'
            + '<path d="M2.2 11.4l3.4-3 2.8 2.4 2.4-2 2.9 2.8"/></svg>';
          // How long after a session is picked a focus in the composer is still dropped, and how long
          // the rail waits before it starts closing, so the navigation is already under way.
          // Sessions are the rail's tree items, which is what tells a row that navigates apart
          // from the rail's own controls.
          var SESSION_ROW = '[role="treeitem"]';
          var WORKSPACE_ROW = '[class*="projectRow"]';
          var SESSION_HANDOFF_MS = 2500;
          var SESSION_HANDOFF_SETTLE_MS = 260;
          // Width of the edge strip the host claims from the system for these gestures, in CSS
          // pixels. It mirrors EDGE_GESTURE_STRIP_DP, which the host sets in device pixels.
          var CLAIMED_EDGE_PX = 32;

          // Pinch limits for an opened image.
          var MIN_SCALE = 1;
          var MAX_SCALE = 8;
          // Window within which two taps count as a double tap.
          var DOUBLE_TAP_MS = 320;
          var DOUBLE_TAP_SLOP = 40;

          /** Release speed across the recent samples, or zero when they are too close together. */
          function velocityOf(state) {
            if (!state.samples || state.samples.length < 2) { return 0; }
            var oldest = state.samples[0];
            var newest = state.samples[state.samples.length - 1];
            var span = newest.at - oldest.at;
            if (span < MIN_VELOCITY_SPAN_MS) { return 0; }
            return (newest.offset - oldest.offset) / span;
          }

          var drag = null;
          var pinch = null;

          var lastTap = { at: 0, x: 0, y: 0 };
          // While this is in the future, a focus that lands in an editable control is dropped.
          var suppressFocusUntil = 0;

          function query(selector) { return document.querySelector(selector); }

          /**
           * Hides the parts of the shell the phone draws for itself, and settles the first-run notice.
           *
           * The rail collapse rule is an override rather than a removal: the harness still owns the
           * element and still toggles its own state attribute, so the gesture keeps driving it.
           */
          /**
           * Mirrors the shell's collapsed state onto an attribute this app's stylesheet can match.
           *
           * The shell sets `data-sidebar-collapsed` on the frame and publishes no other signal, and a
           * `:has()` selector was not safe to build on here. See installShellAdjustments.
           */
          function markRailState() {
            var frame = sidebarFrame();
            if (!frame) { return; }
            var state = frame.hasAttribute('data-sidebar-collapsed') ? 'closed' : 'open';
            if (frame.getAttribute('data-dsh-rail') !== state) {
              frame.setAttribute('data-dsh-rail', state);
            }
          }

          function installShellAdjustments() {
            var style = document.createElement('style');
            // The rail is lifted out of the layout and floated over the content.
            //
            // The shell lays the rail out as a grid column and animates that column's width, so
            // opening it squeezed the centre column and rewrapped every paragraph. The width is an
            // inline style the shell rewrites on each render, so it can only be beaten with
            // `!important`; taking the rail out of the flow then leaves the content at full width,
            // and the centre column is pushed across instead of being resized.
            //
            // The explicit `grid-column` values are load-bearing. A fixed rail no longer occupies a
            // grid cell, so the remaining children are auto-placed one column to the left and the
            // centre column lands in the zero-width first column: the page then renders blank. This
            // was the cause of a blank screen, and the placement rules are what fix it.
            //
            // `:has(> ...)` anchors these rules to the frame, which is the only element carrying the
            // collapsed state. Matching on class alone would also catch the image preview's `frame`.
            style.textContent =
              '[data-sidebar-right-expand] { display: none !important; }' +
              '[data-dsh-rail] { grid-template-columns: 0px 1fr 0px !important; }' +
              '[data-dsh-rail] > [class*="sidebarCol"] { overflow: hidden !important;' +
              ' position: fixed !important; top: 0; bottom: 0; left: 0;' +
              ' width: 280px !important; z-index: 30; transform: translateX(-100%);' +
              ' transition: transform var(--ds-transition-duration-slow) var(--ds-ease-in-out); }' +
              '[data-dsh-rail="open"] > [class*="sidebarCol"] { transform: translateX(0); }' +
              '[data-dsh-rail] > [class*="centerCol"] { grid-column: 2;' +
              ' transition: transform var(--ds-transition-duration-slow) var(--ds-ease-in-out); }' +
              '[data-dsh-rail="open"] > [class*="centerCol"] { transform: translateX(280px); }' +
              '[data-dsh-rail] > [class*="rightbarCol"] { grid-column: 3; }' +
              '#dsh-add-image { display: inline-flex; align-items: center; justify-content: center;' +
              ' width: 28px; height: 28px; padding: 0; border: 0; background: none;' +
              ' color: inherit; cursor: pointer; }';
            document.head.appendChild(style);

            // The rules above hang off an attribute this script maintains, rather than a `:has()`
            // selector. `:has()` worked in a desktop Chromium but left the app's WebView on a blank
            // page while its network activity continued, so the shell's own render was breaking.
            var frame = sidebarFrame();
            if (frame && window.MutationObserver) {
              new MutationObserver(markRailState).observe(frame, {
                attributes: true, attributeFilter: ['data-sidebar-collapsed']
              });
            }
            markRailState();
            window.setInterval(markRailState, RAIL_STATE_POLL_MS);
            window.setInterval(function () {
              var dialogs = document.querySelectorAll('[role="dialog"][aria-modal="true"]');
              for (var i = 0; i < dialogs.length; i++) {
                var dialog = dialogs[i];
                var text = dialog.innerText || '';
                if (!/Internal Testing|测试|test/i.test(text)) { continue; }
                var buttons = dialog.querySelectorAll('button');
                if (!buttons.length) { continue; }
                // The acknowledgement is the last control in that dialog.
                buttons[buttons.length - 1].click();
                return;
              }
            }, 1200);
          }

          /** The left rail's control, matched on its accessible name rather than its class. */
          function sidebarControl() {
            var buttons = document.querySelectorAll('button[aria-label]');
            for (var i = 0; i < buttons.length; i++) {
              var button = buttons[i];
              if (button.hasAttribute('data-sidebar-right-expand')) { continue; }
              if (button.hasAttribute('data-sidebar-right-toggle')) { continue; }
              var label = button.getAttribute('aria-label') || '';
              if (/sidebar|侧边栏|側邊欄/i.test(label)) { return button; }
            }
            return null;
          }

          /**
           * The left rail's own width, which is what says whether it is open.
           *
           * Not read from the frame's grid: the harness lays the shell out as three columns while the
           * rail is collapsed and as a single full width column once it is open, so the column list
           * cannot be used to tell the two states apart.
           */
          function railWidth() {
            var rail = query(SIDEBAR_RAIL);
            return rail ? Math.round(rail.getBoundingClientRect().width) : 0;
          }

          /** The grid the rail lives in, which carries the collapsed state as an attribute. */
          function sidebarFrame() {
            var rail = query(SIDEBAR_RAIL);
            return rail ? rail.parentElement : null;
          }

          /** The right panel's current horizontal offset, in pixels. */
          function rightState() {
            var panel = query(RIGHT_PANEL);
            if (!panel) { return null; }
            var matrix = getComputedStyle(panel).transform.match(/matrix\(([^)]+)\)/);
            return {
              panel: panel,
              width: Math.round(panel.getBoundingClientRect().width),
              offset: matrix ? (parseFloat(matrix[1].split(',')[4]) || 0) : 0
            };
          }

          /**
           * Forces a hidden node, and any hidden ancestor, to be painted.
           *
           * A collapsed right panel is `visibility: hidden`, so a drag that only sets a transform
           * draws nothing and the panel appears fully open in one step.
           *
           * @return what was changed, so exactly those properties can be released afterwards.
           */
          function reveal(node) {
            var touched = [];
            var depth = 0;
            for (var n = node; n && n !== document.body && depth < 5; n = n.parentElement) {
              var style = getComputedStyle(n);
              if (style.visibility === 'hidden' || style.opacity === '0') {
                touched.push({ node: n, visibility: n.style.visibility, opacity: n.style.opacity });
                n.style.visibility = 'visible';
                n.style.opacity = '1';
              }
              depth++;
            }
            return touched;
          }

          function releaseRevealed(touched) {
            (touched || []).forEach(function (entry) {
              if (!entry.node.isConnected) { return; }
              entry.node.style.visibility = entry.visibility;
              entry.node.style.opacity = entry.opacity;
            });
          }

          /** The control that moves a panel to the state the gesture settled on. */
          function controlFor(kind, open) {
            if (kind === 'sidebar') { return sidebarControl(); }
            return query(open ? RIGHT_EXPAND : RIGHT_TOGGLE);
          }

          /** Whether a panel counts as open right now. */
          function isOpen(kind) {
            if (kind === 'sidebar') {
              var frame = sidebarFrame();
              if (frame && frame.hasAttribute('data-sidebar-collapsed')) {
                return frame.getAttribute('data-sidebar-collapsed') !== 'true';
              }
              return railWidth() > RAIL_OPEN_THRESHOLD;
            }
            var right = rightState();
            return right !== null && Math.abs(right.offset) < 1;
          }

          /** Fills in what a gesture is about to move, once the target is known. */
          function attachTarget(state, kind) {
            state.kind = kind;
            state.samples = [{ at: Date.now(), offset: 0 }];
            if (kind === 'sidebar') {
              // The rail is not dragged: the harness's own open and close animation is used instead,
              // and this gesture only decides which way to ask for. See the class comment.
              // Width alone cannot say whether it is open once the rail is hidden, so the harness's
              // own state attribute decides.
              var frame = sidebarFrame();
              if (!frame) { return false; }
              state.railWidth = railWidth();
              state.offset = 0;
              state.current = 0;
              if (frame.hasAttribute('data-sidebar-collapsed')) {
                state.wasOpen = frame.getAttribute('data-sidebar-collapsed') !== 'true';
              } else {
                state.wasOpen = state.railWidth > RAIL_OPEN_THRESHOLD;
              }
            } else {
              var right = rightState();
              if (!right) { return false; }
              state.panel = right.panel;
              state.span = right.width;
              // The panel travels the whole of its own width, so the reach is that width. Leaving
              // this unset made the fraction divide by one and always look like a huge drag.
              state.from = 0;
              state.to = right.width;
              state.offset = right.offset;
              state.current = right.offset;
              state.wasOpen = Math.abs(right.offset) < 1;
              state.revealed = reveal(right.panel);
            }
            state.samples = [{ at: Date.now(), offset: state.current }];
            return true;
          }

          function onStart(event) {
            if (event.touches.length === 2) {
              // A second finger means a pinch, so any drawer drag in progress is abandoned and the
              // drawer gesture stands down until the pinch ends.
              if (drag) { releaseRevealed(drag.revealed); drag = null; }
              beginPinch(event);
              return;
            }
            if (event.touches.length !== 1 || pinch) { return; }
            var touch = event.touches[0];

            // A panel that is already open owns the gesture, wherever the finger lands. With nothing
            // open, the direction of travel decides, once the movement is known.
            var kind = null;
            if (isOpen('sidebar')) { kind = 'sidebar'; }
            else if (isOpen('right')) { kind = 'right'; }

            var state = {
              kind: null, panel: null, grid: null, revealed: null, samples: [],
              from: 0, to: 0, offset: 0, current: 0, openWidth: 0, closedWidth: 0, span: 0,
              startX: touch.clientX, startY: touch.clientY, axis: null, wasOpen: false,
              target: event.target
            };
            if (kind && !attachTarget(state, kind)) { return; }
            drag = state;
          }

          function onMove(event) {
            if (!drag || event.touches.length !== 1) { return; }
            var touch = event.touches[0];
            var dx = touch.clientX - drag.startX;
            var dy = touch.clientY - drag.startY;
            if (!drag.axis) {
              if (Math.abs(dx) < AXIS_SLOP && Math.abs(dy) < AXIS_SLOP) { return; }
              // A mostly vertical movement belongs to the page, so the gesture is abandoned and the
              // scroll proceeds untouched.
              if (Math.abs(dy) >= Math.abs(dx)) { drag = null; return; }
              drag.axis = 'x';
              // A sideways scroller under the finger keeps the gesture while it can still pan, but
              // not inside the edge strip the host claimed from the system: that strip exists for
              // these drawer swipes, so a touch starting there belongs to the drawer even when what
              // sits underneath is a scrollable code block.
              if (!insideClaimedEdge(drag.startX) && horizontalScrollerBlocking(drag.target, dx)) {
                releaseRevealed(drag.revealed);
                drag = null;
                return;
              }
              if (!drag.kind) {
                var towards = dx > 0 ? 'sidebar' : 'right';
                if (!attachTarget(drag, towards) &&
                    !attachTarget(drag, towards === 'sidebar' ? 'right' : 'sidebar')) {
                  drag = null;
                  return;
                }
              }
            }
            // Only reached once the movement is known to be horizontal, so ordinary scrolling never
            // pays for a cancelled event.
            event.preventDefault();
            var next = drag.offset + dx;
            if (drag.kind === 'sidebar') {
              // Deliberately no visual change: the harness animates this panel itself once asked.
            } else {
              next = Math.max(0, Math.min(drag.span, next));
              drag.panel.style.transition = 'none';
              drag.panel.style.transform = 'translateX(' + next + 'px)';
            }
            drag.current = next;
            drag.samples.push({ at: Date.now(), offset: next });
            while (drag.samples.length > 2 && Date.now() - drag.samples[0].at > FLICK_WINDOW_MS) {
              drag.samples.shift();
            }
          }

          function onEnd() {
            if (!drag) { return; }
            var finished = drag;
            drag = null;
            if (finished.axis !== 'x') { releaseRevealed(finished.revealed); return; }

            // Measured from where the gesture started, in the direction it moved.
            var travelled = finished.current - finished.offset;
            var towardsOpen = finished.kind === 'sidebar' ? travelled > 0 : travelled < 0;
            var shouldOpen;
            if (finished.kind === 'sidebar') {
              // No drag to measure, so the swipe only has to be deliberate: far enough, or fast
              // enough to count as a throw.
              var wanted = Math.abs(travelled) >= SIDEBAR_COMMIT_PX ||
                Math.abs(velocityOf(finished)) >= FLICK_VELOCITY;
              shouldOpen = wanted ? towardsOpen : finished.wasOpen;
            } else {
              var reach = Math.max(1, Math.abs(finished.to - finished.from));
              var fraction = Math.abs(travelled) / reach;
              shouldOpen = fraction > COMMIT_FRACTION ? towardsOpen : finished.wasOpen;
            }

            // Speed across the recent window, so a throw is recognised even when the finger slowed
            // just before it lifted, and a pair of samples a millisecond apart cannot look enormous.
            var velocity = velocityOf(finished);
            if (finished.kind === 'sidebar') {
              // Nothing to settle: the harness animates the panel when its control is clicked below.
            } else {
              if (Math.abs(velocity) >= FLICK_VELOCITY) { shouldOpen = velocity < 0; }
              finished.panel.style.transition =
                'transform ' + SETTLE_MS + 'ms cubic-bezier(0.4, 0, 0.2, 1)';
              finished.panel.style.transform =
                'translateX(' + (shouldOpen ? 0 : finished.span) + 'px)';
            }

            var changed = shouldOpen !== finished.wasOpen;
            window.setTimeout(function () {
              if (changed) {
                var control = controlFor(finished.kind, shouldOpen);
                if (control) { control.click(); }
              }
              window.setTimeout(function () {
                if (finished.kind === 'sidebar') {
                  // Nothing was overridden for the rail.
                } else {
                  finished.panel.style.transition = '';
                  finished.panel.style.transform = '';
                }
                releaseRevealed(finished.revealed);
              }, changed ? HANDOVER_MS : 0);
            }, SETTLE_MS);
          }

          /**
           * Reports whether a touch started in the edge strip the host claimed from the system.
           *
           * Those strips are exactly the part of each edge that the system does not take for its own
           * back gesture, so a touch there is already known to be meant for a drawer. Giving the
           * drawer priority over whatever is underneath matters because the shell shows code blocks
           * that scroll sideways right up to the screen edge, and a drawer swipe from the edge would
           * otherwise be handed to the code block.
           */
          function insideClaimedEdge(clientX) {
            return clientX <= CLAIMED_EDGE_PX ||
              clientX >= window.innerWidth - CLAIMED_EDGE_PX;
          }

          /**
           * Reports whether the touch landed on something that can still pan the way the finger is
           * going.
           *
           * A swipe that starts inside a sideways scroller belongs to that scroller, so the drawer
           * stands down. Once the scroller has reached its own edge there is nothing left to pan and
           * the drawer takes the gesture over. Testing the direction is what makes a drawer swipe
           * work from inside a row that happens to sit in a sideways scrollable strip, which is where
           * the previous position-only check gave up and left the drawer stuck open.
           *
           * `scrollWidth` alone is not enough to call something a scroller: a container with
           * `overflow-x: hidden` still reports content wider than its box even though a finger can
           * never scroll it.
           */
          function horizontalScrollerBlocking(target, dx) {            for (var node = target; node && node !== document.body; node = node.parentElement) {
              var style = getComputedStyle(node);
              if (style.overflowX !== 'auto' && style.overflowX !== 'scroll') { continue; }
              if (node.scrollWidth <= node.clientWidth + 2) { continue; }
              // What is left to pan in the direction the finger is travelling.
              var room = dx < 0
                ? node.scrollWidth - node.clientWidth - node.scrollLeft
                : node.scrollLeft;
              if (room > 1) { return true; }
            }
            return false;
          }

          //#region image pinch

          function imageElement() {
            var frame = query(IMAGE_FRAME);
            return frame ? frame.querySelector('img') : null;
          }

          function applyImage(state) {
            state.image.style.transformOrigin = '0 0';
            state.image.style.transform =
              'translate(' + state.x + 'px, ' + state.y + 'px) scale(' + state.scale + ')';
          }

          function resetImage(state) {
            state.image.style.transform = '';
            state.image.style.transformOrigin = '';
            state.image.style.touchAction = '';
          }

          function beginPinch(event) {
            var image = imageElement();
            if (!image) { return; }
            var a = event.touches[0];
            var b = event.touches[1];
            var distance = Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY);
            if (distance < 8) { return; }
            // A new image starts from fit; the same image keeps whatever the last pinch left.
            if (!pinch || pinch.image !== image) {
              pinch = { image: image, scale: 1, x: 0, y: 0 };
            } else {
              pinch.image.style.touchAction = 'none';
            }
            pinch.image.style.touchAction = 'none';
            pinch.startDistance = distance;
            pinch.startScale = pinch.scale;
            pinch.startMidX = (a.clientX + b.clientX) / 2;
            pinch.startMidY = (a.clientY + b.clientY) / 2;
            pinch.startX = pinch.x;
            pinch.startY = pinch.y;
            pinch.moved = false;
          }

          function updatePinch(event) {
            if (!pinch || event.touches.length !== 2 || !pinch.startDistance) { return; }
            event.preventDefault();
            var a = event.touches[0];
            var b = event.touches[1];
            var distance = Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY);
            var midX = (a.clientX + b.clientX) / 2;
            var midY = (a.clientY + b.clientY) / 2;

            var scale = pinch.startScale * (distance / pinch.startDistance);
            scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
            var ratio = scale / pinch.startScale;
            pinch.scale = scale;
            // The image point that was under the midpoint stays under it, so the pinch is anchored
            // between the fingers rather than at the image corner.
            pinch.x = midX - (pinch.startMidX - pinch.startX) * ratio;
            pinch.y = midY - (pinch.startMidY - pinch.startY) * ratio;
            pinch.moved = true;
            applyImage(pinch);
          }

          function endPinch() {
            if (!pinch) { return; }
            if (pinch.moved && pinch.scale <= MIN_SCALE + 0.01) {
              // Back to fit, so the harness owns the image again.
              resetImage(pinch);
              pinch = null;
            }
          }

          function onImageStart(event) {
            if (event.touches.length !== 1 || !pinch) { return; }
            var touch = event.touches[0];
            pinch.panFromX = touch.clientX;
            pinch.panFromY = touch.clientY;
            pinch.panOriginX = pinch.x;
            pinch.panOriginY = pinch.y;
            pinch.panning = false;
          }

          function onImageMove(event) {
            if (!pinch || event.touches.length !== 1 || pinch.panFromX === undefined) { return; }
            var touch = event.touches[0];
            var dx = touch.clientX - pinch.panFromX;
            var dy = touch.clientY - pinch.panFromY;
            if (!pinch.panning && Math.abs(dx) + Math.abs(dy) > AXIS_SLOP) { pinch.panning = true; }
            if (!pinch.panning) { return; }
            // Claimed, so the drawer does not also read this drag.
            event.preventDefault();
            pinch.x = pinch.panOriginX + dx;
            pinch.y = pinch.panOriginY + dy;
            applyImage(pinch);
          }

          function onImageEnd() {
            if (pinch) { pinch.panFromX = undefined; pinch.panning = false; }
          }

          /** A double tap returns a scaled image to fit, so there is always a way back. */
          function onDoubleTap(event) {
            var touch = event.changedTouches && event.changedTouches[0];
            if (!touch) { return; }
            var now = Date.now();
            var isDouble = now - lastTap.at <= DOUBLE_TAP_MS &&
              Math.abs(touch.clientX - lastTap.x) <= DOUBLE_TAP_SLOP &&
              Math.abs(touch.clientY - lastTap.y) <= DOUBLE_TAP_SLOP;
            lastTap = { at: now, x: touch.clientX, y: touch.clientY };
            if (!isDouble || !pinch) { return; }
            resetImage(pinch);
            pinch = null;
          }

          //#endregion

          /**
           * Hands the screen over when a session is picked from the rail.
           *
           * Picking a session navigates, and the shell then focuses the composer, which raises the
           * on-screen keyboard on top of the conversation that was just opened. The rail is also
           * left open, so the new conversation arrives squeezed behind it.
           *
           * The focus is dropped rather than prevented: the keyboard follows the focus, and the
           * shell sets it after the session has loaded, so a single blur at click time would be too
           * early. Anything editable focused inside the window is blurred instead.
           */
          function installSessionHandoff() {
            document.addEventListener('click', function (event) {
              if (!isOpen('sidebar')) { return; }
              var rail = query(SIDEBAR_RAIL);
              if (!rail || !rail.contains(event.target)) { return; }
              var clicked = event.target;
              if (!clicked || !clicked.closest) { return; }
              // The per-row menu button sits inside the row; opening that menu must not close
              // the rail, so only the row itself counts.
              if (clicked.closest('button')) { return; }
              if (!clicked.closest(SESSION_ROW)) { return; }
              // Workspaces are tree items too, but a workspace row expands its folder rather than
              // opening a conversation, so it must not close the rail. Without this the rail shut
              // as soon as a folder was tapped, which reads as being thrown back to the chat.
              if (clicked.closest(WORKSPACE_ROW)) { return; }
              suppressFocusUntil = Date.now() + SESSION_HANDOFF_MS;
              window.setTimeout(function () {
                if (!isOpen('sidebar')) { return; }
                var control = controlFor('sidebar', false);
                if (control) { control.click(); }
              }, SESSION_HANDOFF_SETTLE_MS);
            }, { passive: true, capture: true });

            document.addEventListener('focusin', function (event) {
              if (Date.now() > suppressFocusUntil) { return; }
              var node = event.target;
              if (!node || !node.closest) { return; }
              if (!node.closest('textarea, input, [contenteditable="true"]')) { return; }
              node.blur();
            }, { passive: true, capture: true });
          }


          /**
           * Adds a button that goes straight to the phone's pictures.
           *
           * The shell's attachment button opens its own picker, which leaves the owner hunting for
           * the image folder. This button drives the same hidden input, but marks the request as an
           * image one first, and the host opens the gallery when it sees that marker.
           */
          function installImageButton() {
            window.setInterval(function () {
              if (query('#' + ADD_IMAGE_ID)) { return; }
              var input = query(FILE_INPUT);
              var reference = input && input.previousElementSibling;
              // This button is inserted after the shell's, so on a later pass the input's previous
              // sibling is this button rather than the shell's. Step back over it so the anchor
              // does not drift.
              if (reference && reference.id === ADD_IMAGE_ID) {
                reference = reference.previousElementSibling;
              }
              if (!reference || reference.tagName !== 'BUTTON') { return; }
              var button = document.createElement('button');
              button.id = ADD_IMAGE_ID;
              button.type = 'button';
              // The shell's own class, so the button matches its neighbours without a second set of
              // styles to keep in step.
              button.className = reference.className;
              button.setAttribute('aria-label', ADD_IMAGE_LABEL);
              button.innerHTML = ADD_IMAGE_ICON;
              button.addEventListener('click', function (event) {
                event.preventDefault();
                event.stopPropagation();
                var field = query(FILE_INPUT);
                if (!field) { return; }
                // The marker goes through the host bridge, not the input's accept attribute: the
                // shell renders that input and strips attributes it did not set, so an accept set
                // here is gone by the time the chooser request reaches the host.
                if (window.DshHost && window.DshHost.requestImagePicker) {
                  window.DshHost.requestImagePicker();
                }
                field.click();
              });
              reference.parentElement.insertBefore(button, reference.nextSibling);
            }, IMAGE_BUTTON_POLL_MS);
          }

          installShellAdjustments();
          installImageButton();
          installSessionHandoff();

          document.addEventListener('touchstart', function (event) {
            onStart(event);
            onImageStart(event);
          }, { passive: true, capture: true });

          /**
           * Drawer control for the host app.
           *
           * The claimed edge band covers only part of each edge, so a swipe that starts outside it
           * arrives as the system back gesture instead. The host asks this before leaving the app, so
           * that back closes an open drawer rather than exiting on top of it.
           *
           * @returns whether a drawer was open and has been asked to close.
           */
          window.__dshDrawers = {
            closeAny: function () {
              var kind = null;
              if (isOpen('sidebar')) { kind = 'sidebar'; }
              else if (isOpen('right')) { kind = 'right'; }
              if (!kind) { return false; }
              var control = controlFor(kind, false);
              if (!control) { return false; }
              control.click();
              return true;
            }
          };

          document.addEventListener('touchmove', function (event) {
            if (pinch) {
              if (event.touches.length === 2) { updatePinch(event); } else { onImageMove(event); }
              return;
            }
            onMove(event);
          }, { passive: false, capture: true });

          document.addEventListener('touchend', function (event) {
            var wasPinching = pinch !== null;
            onDoubleTap(event);
            onImageEnd();
            // A pinch that has ended stops owning the sequence, and a drawer drag only ends when it
            // was the thing being driven.
            endPinch();
            if (!wasPinching) { onEnd(); }
          }, { passive: true, capture: true });

          document.addEventListener('touchcancel', function () {
            endPinch();
            onEnd();
          }, { passive: true, capture: true });
        })();
    """.trimIndent()
}
