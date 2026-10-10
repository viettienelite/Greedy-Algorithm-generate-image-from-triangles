import {Triangle, Rectangle, Ellipse, Smiley} from "./shape.js";
import {clamp} from "./util.js";

const numberFields = ["computeSize", "viewSize", "steps", "shapes", "alpha", "mutations"];
const boolFields = ["mutateAlpha"];
const fillField = "fill";
const shapeField = "shapeType";
const shapeMap = {
	"triangle": Triangle,
	"rectangle": Rectangle,
	"ellipse": Ellipse,
	"smiley": Smiley
}

const HEX_RE = /^#?([0-9a-f]{3}|[0-9a-f]{6})$/i;

/* number of decimals implied by a step attribute ("0.05" => 2, "1" => 0, "any" => 2) */
function decimals(input) {
	let step = input.getAttribute("step");
	if (!step || step == "any") { return 2; }
	let dot = step.indexOf(".");
	return (dot == -1 ? 0 : step.length - dot - 1);
}

function limits(input) {
	let min = input.min === "" ? -Infinity : Number(input.min);
	let max = input.max === "" ? Infinity : Number(input.max);
	return {min, max};
}

/* read a number input; empty / invalid / out-of-range values are fixed to something valid */
function readNumber(input) {
	let {min, max} = limits(input);
	let value = Number(input.value);
	if (input.value.trim() === "" || !isFinite(value)) { value = Number(input.defaultValue); }
	return clamp(value, min, max);
}

function formatNumber(input, value) {
	if (input.getAttribute("step") == "any") { return String(parseFloat(value.toFixed(2))); }
	return value.toFixed(decimals(input));
}

/* a slider (optional) and a number input that mirror each other */
function bindNumber(number) {
	let range = document.querySelector(`input[type=range][data-for=${number.name}]`);

	function fromRange() {
		number.value = formatNumber(number, Number(range.value));
	}

	function toRange() {
		if (!range || number.value.trim() === "" || !isFinite(Number(number.value))) { return; }
		range.value = number.value; /* the browser clamps to the slider's own min/max */
	}

	function normalize() {
		let value = readNumber(number);
		number.value = formatNumber(number, value);
		toRange();
	}

	if (range) { range.addEventListener("input", fromRange); }
	number.addEventListener("input", toRange);
	number.addEventListener("change", normalize);
	number.addEventListener("blur", normalize);
	number.addEventListener("keydown", e => {
		if (e.key == "Enter") { normalize(); }
	});

	normalize();
}

/* colour picker + hex text field */
function bindColor() {
	let picker = document.querySelector("[name='fill-color']");
	let text = document.querySelector("[name='fill-hex']");
	if (!picker || !text) { return; }

	function expand(value) {
		let match = value.trim().match(HEX_RE);
		if (!match) { return null; }
		let hex = match[1].toLowerCase();
		if (hex.length == 3) { hex = hex.split("").map(c => c + c).join(""); }
		return `#${hex}`;
	}

	picker.addEventListener("input", () => {
		text.value = picker.value;
		text.classList.remove("invalid");
		selectFixed();
	});

	text.addEventListener("input", () => {
		let hex = expand(text.value);
		text.classList.toggle("invalid", !hex);
		if (hex) { picker.value = hex; selectFixed(); }
	});

	text.addEventListener("blur", () => {
		text.value = picker.value;
		text.classList.remove("invalid");
	});

	text.value = picker.value;
}

/* touching the colour controls implies "use a fixed colour" */
function selectFixed() {
	let radio = document.querySelector("[name=fill][value=fixed]");
	if (radio) { radio.checked = true; }
}

/* enable / disable the size-limit inputs together with their checkbox */
function bindSizeLimit() {
	let toggle = document.querySelector("[name=limitSize]");
	let controls = Array.from(document.querySelectorAll("[data-size-limit]"));

	function sync() {
		controls.forEach(node => node.disabled = !toggle.checked);
		toggle.closest(".limit").classList.toggle("on", toggle.checked);
	}

	toggle.addEventListener("change", sync);
	sync();
}

export function init() {
	let numbers = Array.from(document.querySelectorAll("input[type=number][name]"));
	numbers.forEach(bindNumber);
	bindColor();
	bindSizeLimit();
}

/* lock the form while a run is in progress */
export function lock(locked) {
	let form = document.querySelector("form");
	form.classList.toggle("running", locked);
	document.querySelector("#start").disabled = locked;
	document.querySelector("#stop").disabled = !locked;
}

export function getConfig() {
	let form = document.querySelector("form");
	let cfg = {};

	numberFields.forEach(name => {
		cfg[name] = readNumber(form.querySelector(`[name=${name}]`));
	});

	boolFields.forEach(name => {
		cfg[name] = form.querySelector(`[name=${name}]`).checked;
	});

	cfg.shapeTypes = [];
	let shapeFields = Array.from(form.querySelectorAll(`[name=${shapeField}]`));
	shapeFields.forEach(input => {
		if (!input.checked) { return; }
		cfg.shapeTypes.push(shapeMap[input.value]);
	});

	let fillFields = Array.from(form.querySelectorAll(`[name=${fillField}]`));
	fillFields.forEach(input => {
		if (!input.checked) { return; }

		switch (input.value) {
			case "auto": cfg.fill = "auto"; break;
			case "fixed": cfg.fill = form.querySelector("[name='fill-color']").value; break;
		}
	});

	/* target SVG file size, converted to bytes. 0 = no limit */
	cfg.maxBytes = 0;
	if (form.querySelector("[name=limitSize]").checked) {
		let amount = readNumber(form.querySelector("[name=targetSize]"));
		let unit = Number(form.querySelector("[name=targetUnit]").value);
		cfg.maxBytes = Math.max(1, Math.floor(amount * unit));
	}

	return cfg;
}
