document.addEventListener("DOMContentLoaded", () => {
  for (const button of document.querySelectorAll(".onesa-reveal")) {
    const input = document.getElementById(button.getAttribute("aria-controls"));
    button.addEventListener("click", () => {
      const shown = input.type === "password";
      input.type = shown ? "text" : "password";
      button.setAttribute("aria-pressed", String(shown));
      button.setAttribute("aria-label", shown ? button.dataset.hideLabel : button.dataset.showLabel);
      input.focus();
    });
  }
});
