import * as ui from "./ui.js";
import Canvas from "./canvas.js";
import Optimizer from "./optimizer.js";

const nodes = {
	output: document.querySelector("#output"),
	original: document.querySelector("#original"),
	status: document.querySelector("#status"),
	size: document.querySelector("#size-readout"),
	meter: document.querySelector("#meter"),
	meterBar: document.querySelector("#meter-bar"),
	raster: document.querySelector("#raster"),
	vector: document.querySelector("#vector"),
	vectorText: document.querySelector("#vector-text"),
	message: document.querySelector("#form-message"),
	stop: document.querySelector("#stop"),
	downloadSvg: document.querySelector("#download-svg"),
	downloadPng: document.querySelector("#download-png"),
	copySvg: document.querySelector("#copy-svg"),
	types: Array.from(document.querySelectorAll("#output [name=type]"))
}

const encoder = new TextEncoder();

let current = null; /* the optimizer that is currently running */
let resultCanvas = null;
let shapeCount = 0;

/* real size of a string once saved as a UTF-8 file */
function byteLength(str) {
	return encoder.encode(str).length;
}

function formatBytes(bytes) {
	if (bytes < 1024) { return `${bytes} B`; }
	if (bytes < 1024*1024) { return `${(bytes/1024).toFixed(2)} KB`; }
	return `${(bytes/(1024*1024)).toFixed(2)} MB`;
}

function showMessage(text) {
	nodes.message.textContent = text;
	nodes.message.hidden = !text;
}

function setExportEnabled(enabled) {
	[nodes.downloadSvg, nodes.downloadPng, nodes.copySvg].forEach(button => button.disabled = !enabled);
}

function updateProgress(fraction) {
	let percent = Math.round(100 * Math.min(1, Math.max(0, fraction)));
	nodes.meter.setAttribute("aria-valuenow", percent);
	nodes.meterBar.style.width = `${percent}%`;
}

function describe(reason, cfg, bytes) {
	switch (reason) {
		case "size":
			if (!shapeCount) { return "Giới hạn dung lượng quá nhỏ: chưa thêm được hình nào."; }
			return `Đã dừng vì đạt giới hạn dung lượng ${formatBytes(cfg.maxBytes)}.`;
		case "stopped": return "Đã dừng theo yêu cầu.";
		default: return `Hoàn tất: đã chạy đủ ${cfg.steps} lượt.`;
	}
}

function go(original, cfg) {
	ui.lock(true);
	showMessage("");
	setExportEnabled(false);

	nodes.status.textContent = "";
	nodes.size.textContent = "";
	nodes.original.innerHTML = "";
	nodes.raster.innerHTML = "";
	nodes.vector.innerHTML = "";
	nodes.vectorText.value = "";
	updateProgress(0);

	nodes.output.hidden = false;
	nodes.original.appendChild(original.node);

	let optimizer = new Optimizer(original, cfg);
	current = optimizer;
	shapeCount = 0;

	/* the viewing size can be tiny, but a canvas / svg must stay at least 1px */
	let cfg2 = Object.assign({}, cfg, {
		width: Math.max(1, Math.round(cfg.scale*cfg.width)),
		height: Math.max(1, Math.round(cfg.scale*cfg.height))
	});
	let result = Canvas.empty(cfg2, false);
	result.ctx.scale(cfg.scale, cfg.scale);
	nodes.raster.appendChild(result.node);
	resultCanvas = result;

	let svg = Canvas.empty(cfg, true);
	svg.setAttribute("width", cfg2.width);
	svg.setAttribute("height", cfg2.height);
	nodes.vector.appendChild(svg);

	let serializer = new XMLSerializer();
	let bytes = byteLength(serializer.serializeToString(svg));
	let similarity = null;

	function renderStatus() {
		let text = `${shapeCount} hình`;
		if (similarity !== null) { text += `, giống ${similarity}%`; }
		nodes.status.textContent = text;

		let size = `Dung lượng SVG: ${formatBytes(bytes)}`;
		if (cfg.maxBytes) { size += ` / ${formatBytes(cfg.maxBytes)}`; }
		nodes.size.textContent = size;

		let byShapes = shapeCount / cfg.steps;
		let bySize = (cfg.maxBytes ? bytes / cfg.maxBytes : 0);
		updateProgress(Math.max(byShapes, bySize));
	}

	optimizer.onStep = (step) => {
		if (!step) { return; } /* discarded: the shape did not improve the image */

		let node = step.toSVG();
		svg.appendChild(node);
		let text = serializer.serializeToString(svg);
		let newBytes = byteLength(text);

		if (cfg.maxBytes && newBytes > cfg.maxBytes) {
			/* this shape would push the file over the target: drop it and stop here */
			svg.removeChild(node);
			optimizer.stop("size");
			return;
		}

		bytes = newBytes;
		shapeCount++;
		similarity = (100*(1-step.distance)).toFixed(2);
		result.drawStep(step);
		nodes.vectorText.value = text;
		renderStatus();
	}

	optimizer.onFinish = (reason) => {
		current = null;
		ui.lock(false);

		renderStatus();
		if (reason == "done" || reason == "size") { updateProgress(1); }

		nodes.vectorText.value = serializer.serializeToString(svg);
		setExportEnabled(shapeCount > 0);
		showMessage(describe(reason, cfg, bytes));
	}

	renderStatus();
	optimizer.start();

	nodes.output.scrollIntoView({block: "start"});
}

function onSubmit(e) {
	e.preventDefault();
	if (current) { return; }

	let inputFile = document.querySelector("input[type=file]");
	let inputUrl = document.querySelector("input[name=url]");

	let url = "test";
	if (inputFile.files.length > 0) {
		let file = inputFile.files[0];
		url = URL.createObjectURL(file);
	} else if (inputUrl.value) {
		url = inputUrl.value;
	}

	let cfg = ui.getConfig();

	if (!cfg.shapeTypes.length) {
		showMessage("Hãy chọn ít nhất một loại hình.");
		return;
	}

	showMessage("");
	Canvas.original(url, cfg).then(original => go(original, cfg));
}

function download(blob, name) {
	let a = document.createElement("a");
	a.href = URL.createObjectURL(blob);
	a.download = name;
	document.body.appendChild(a);
	a.click();
	a.remove();
	setTimeout(() => URL.revokeObjectURL(a.href), 1000);
}

function onDownloadSvg() {
	let blob = new Blob([nodes.vectorText.value], {type: "image/svg+xml;charset=utf-8"});
	download(blob, `primitive-${shapeCount}-hinh.svg`);
}

function onDownloadPng() {
	if (!resultCanvas) { return; }
	resultCanvas.node.toBlob(blob => download(blob, `primitive-${shapeCount}-hinh.png`), "image/png");
}

function onCopySvg() {
	let text = nodes.vectorText.value;
	let done = () => {
		nodes.copySvg.textContent = "Đã sao chép";
		setTimeout(() => nodes.copySvg.textContent = "Sao chép SVG", 1500);
	}

	if (navigator.clipboard && navigator.clipboard.writeText) {
		navigator.clipboard.writeText(text).then(done);
	} else {
		nodes.vectorText.select();
		document.execCommand("copy");
		done();
	}
}

function init() {
	nodes.output.hidden = true;
	nodes.types.forEach(input => input.addEventListener("click", syncType));
	ui.init();
	ui.lock(false);
	setExportEnabled(false);
	syncType();

	document.querySelector("form").addEventListener("submit", onSubmit);
	nodes.stop.addEventListener("click", () => current && current.stop("stopped"));
	nodes.downloadSvg.addEventListener("click", onDownloadSvg);
	nodes.downloadPng.addEventListener("click", onDownloadPng);
	nodes.copySvg.addEventListener("click", onCopySvg);
}

function syncType() {
	nodes.output.classList.remove("raster", "vector");
	nodes.types.forEach(input => {
		if (input.checked) { nodes.output.classList.add(input.value); }
	});
}

init();
