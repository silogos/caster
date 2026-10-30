/**
 * Production WebGL2 canvas that turns video frames into the exact byte buffer
 * softcam wants (ADR-007): BGR, 3 bytes/pixel, top-down, tightly packed — all
 * on the GPU, so the main process never touches a pixel.
 *
 * Two passes:
 *   1. scale: the VideoFrame is drawn (linear-filtered, letterboxed) into an
 *      offscreen texture at the camera's fixed WxH;
 *   2. pack: a byte-packing shader reads that texture and writes 4 consecutive
 *      BGR bytes per output pixel — the canvas is (W*3/4) x H pixels, so
 *      readPixels(RGBA) yields exactly W*H*3 packed bytes.
 *
 * The hardware/GPU boundary — like softcamLibrary on the main side, it is not
 * node-testable; the orchestration around it (framePump) is.
 */

const SCALE_VS = `#version 300 es
layout(location=0) in vec2 a_pos;
out vec2 v_uv;
void main() {
  // Letterbox: map the unit quad to the largest rect with the source aspect
  // inside the fixed target, centered (black bars fill the rest).
  v_uv = a_pos * 0.5 + 0.5;
  gl_Position = vec4(a_pos, 0.0, 1.0);
}`

const SCALE_FS = `#version 300 es
precision mediump float;
uniform sampler2D u_source;
uniform vec2 u_srcAspect;   // source width/height
uniform vec2 u_dstAspect;   // destination (camera) width/height
in vec2 v_uv;
out vec4 outColor;
void main() {
  // Fit u_srcAspect inside u_dstAspect ("contain"): the source fully visible,
  // black bars where the aspects differ — the middle band of the destination
  // maps to the full source, the rest stays black (the ADR letterbox rule).
  float ratio = (u_srcAspect.x / u_srcAspect.y) / (u_dstAspect.x / u_dstAspect.y);
  vec2 uv = v_uv;
  if (ratio > 1.0) {
    uv.y = (uv.y - 0.5) * ratio + 0.5;  // source wider: bars top/bottom
  } else if (ratio < 1.0) {
    uv.x = (uv.x - 0.5) / ratio + 0.5;  // source taller: bars left/right
  }
  outColor = (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0)
    ? vec4(0.0)
    : texture(u_source, uv);
}`

/**
 * Pass 2 — the packing shader. Output pixel x' holds BGR bytes
 * [3x', 3x'+1, 3x'+2] of the *next* pixel's B... precisely: output channel k
 * carries stream byte (4x'+k), and stream byte i belongs to source pixel
 * i/3, channel i%3, where channel 0=B, 1=G, 2=R. Integer math via texelFetch.
 * Rows are flipped (readPixels reads bottom-up; softcam wants top-down).
 */
const PACK_FS = `#version 300 es
precision mediump float;
uniform sampler2D u_scaled;
uniform int u_srcWidth;
uniform int u_srcHeight;
in vec2 v_uv;
out vec4 outColor;
void main() {
  // gl_FragCoord is bottom-left origin; flip to the top-down row order the
  // camera buffer needs, then to integer texel space.
  int y = u_srcHeight - 1 - int(gl_FragCoord.y);
  int x4 = int(gl_FragCoord.x) * 4;   // first stream byte of this output pixel
  vec3 tri0 = texelFetch(u_scaled, ivec2(x4 / 3, y), 0).rgb;
  vec3 tri1 = texelFetch(u_scaled, ivec2((x4 + 3) / 3, y), 0).rgb;
  // channel c (0=B,1=G,2=R) of source pixel p, with p spanning at most x4/3..x4/3+1
  vec3 pick;
  vec4 bytes;
  for (int k = 0; k < 4; k++) {
    int i = x4 + k;
    int p = i / 3;
    int c = i - p * 3;
    pick = p == x4 / 3 ? tri0 : tri1;
    float channel = c == 0 ? pick.b : (c == 1 ? pick.g : pick.r);
    if (k == 0) { bytes.x = channel; } else if (k == 1) { bytes.y = channel; } else if (k == 2) { bytes.z = channel; } else { bytes.w = channel; }
  }
  outColor = bytes;
}`

export interface BgrCanvas {
  /** Draws one decoded video frame (scaled + letterboxed into the fixed camera size). */
  drawFrame(frame: unknown): void
  /** Reads the packed BGR24 buffer (width×height×3 bytes, top-down). */
  readPixels(out: Uint8Array): void
  /** Context-loss probe (WebGL_CONTEXT_LOST). */
  isLost(): boolean
}

export function createBgrCanvas(canvas: HTMLCanvasElement, width: number, height: number): BgrCanvas {
  const gl = canvas.getContext('webgl2', { alpha: false, antialias: false, preserveDrawingBuffer: true })
  if (gl === null) throw new Error('WebGL2 is unavailable in this Chromium')
  canvas.width = (width * 3) / 4
  canvas.height = height

  const compile = (type: number, source: string): WebGLShader => {
    const shader = gl.createShader(type)!
    gl.shaderSource(shader, source)
    gl.compileShader(shader)
    if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
      throw new Error(`shader compile failed: ${gl.getShaderInfoLog(shader)}`)
    }
    return shader
  }
  const link = (fsSource: string): WebGLProgram => {
    const program = gl.createProgram()!
    gl.attachShader(program, compile(gl.VERTEX_SHADER, SCALE_VS))
    gl.attachShader(program, compile(gl.FRAGMENT_SHADER, fsSource))
    gl.linkProgram(program)
    if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
      throw new Error(`program link failed: ${gl.getProgramInfoLog(program)}`)
    }
    return program
  }

  const quad = gl.createBuffer()!
  gl.bindBuffer(gl.ARRAY_BUFFER, quad)
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW)

  // Offscreen texture at the camera's fixed size (pass-1 target).
  const scaledTexture = gl.createTexture()!
  gl.bindTexture(gl.TEXTURE_2D, scaledTexture)
  gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, width, height, 0, gl.RGBA, gl.UNSIGNED_BYTE, null)
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR)
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR)
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE)
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
  const scaledFbo = gl.createFramebuffer()!
  gl.bindFramebuffer(gl.FRAMEBUFFER, scaledFbo)
  gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, scaledTexture, 0)
  gl.bindFramebuffer(gl.FRAMEBUFFER, null)

  const scaleProgram = link(SCALE_FS)
  const packProgram = link(PACK_FS)

  const drawQuad = (program: WebGLProgram): void => {
    gl.useProgram(program)
    gl.bindBuffer(gl.ARRAY_BUFFER, quad)
    const loc = gl.getAttribLocation(program, 'a_pos')
    gl.enableVertexAttribArray(loc)
    gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0)
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4)
  }

  canvas.addEventListener('webglcontextlost', (event) => event.preventDefault())

  return {
    drawFrame(frame: unknown) {
      if (this.isLost()) return
      const source = frame as TexImageSource & { displayWidth?: number; displayHeight?: number; width?: number; height?: number }
      const sourceWidth = source.displayWidth ?? source.width ?? width
      const sourceHeight = source.displayHeight ?? source.height ?? height

      // Pass 1: video frame → scaled texture (letterboxed to width×height).
      gl.bindFramebuffer(gl.FRAMEBUFFER, scaledFbo)
      gl.viewport(0, 0, width, height)
      gl.activeTexture(gl.TEXTURE0)
      gl.bindTexture(gl.TEXTURE_2D, scaledTexture)
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, source)
      gl.useProgram(scaleProgram)
      gl.uniform1i(gl.getUniformLocation(scaleProgram, 'u_source'), 0)
      gl.uniform2f(gl.getUniformLocation(scaleProgram, 'u_srcAspect'), sourceWidth, sourceHeight)
      gl.uniform2f(gl.getUniformLocation(scaleProgram, 'u_dstAspect'), width, height)
      drawQuad(scaleProgram)

      // Pass 2: scaled texture → packed BGR canvas ((width*3/4) × height).
      gl.bindFramebuffer(gl.FRAMEBUFFER, null)
      gl.viewport(0, 0, canvas.width, canvas.height)
      gl.bindTexture(gl.TEXTURE_2D, scaledTexture)
      gl.useProgram(packProgram)
      gl.uniform1i(gl.getUniformLocation(packProgram, 'u_scaled'), 0)
      gl.uniform1i(gl.getUniformLocation(packProgram, 'u_srcWidth'), width)
      gl.uniform1i(gl.getUniformLocation(packProgram, 'u_srcHeight'), height)
      drawQuad(packProgram)
    },
    readPixels(out) {
      gl.readPixels(0, 0, canvas.width, canvas.height, gl.RGBA, gl.UNSIGNED_BYTE, out)
    },
    isLost() {
      return gl.isContextLost()
    }
  }
}
