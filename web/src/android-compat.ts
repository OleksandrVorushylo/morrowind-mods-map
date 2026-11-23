if (!Element.prototype.replaceChildren) {
  Element.prototype.replaceChildren = function (...nodes: (Node | string)[]): void {
    this.textContent = ''
    this.append(...nodes)
  }
}

const flexGapTest = document.createElement('div')
flexGapTest.style.cssText = 'position:absolute;top:-9999px;display:flex;flex-direction:column;row-gap:1px'
for (let index = 0; index < 2; index++) {
  const child = document.createElement('div')
  child.style.height = '1px'
  flexGapTest.append(child)
}
document.body.append(flexGapTest)
if (flexGapTest.scrollHeight !== 3) document.documentElement.classList.add('no-flex-gap')
flexGapTest.remove()
