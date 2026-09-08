import { createPortal } from 'react-dom'

type BrandLoadingBlockProps = {
  /** 主标题文案。 */
  title?: string
  /** 次级说明文案。 */
  subtitle?: string
  /** 组件尺寸规格。 */
  size?: 'sm' | 'md' | 'lg'
  /** 是否以遮罩模式覆盖当前容器。 */
  overlay?: boolean
  /** 遮罩模式下是否通过 body portal 渲染为全局层。 */
  portalToBody?: boolean
  /** 非遮罩模式下的最小高度。 */
  minHeight?: number | string
}

type BrandMarkProps = {
  /** N 色块尺寸。 */
  size: number
  /** 是否启用品牌色块动效。 */
  animated?: boolean
}

type AnimatedBrandSealProps = {
  /** 中心 N 色块尺寸。 */
  markSize: number
  /** 外层光晕区域尺寸。 */
  haloSize: number
  /** 动画强度。 */
  intensity?: 'normal' | 'strong'
  /** 视觉形态：seal=强调型加载徽章；ambient=轻量常驻徽章；minimal=极简角标。 */
  variant?: 'seal' | 'ambient' | 'minimal'
}

type LoadingBrandSpinnerProps = {
  /** 中心 logo 尺寸。 */
  markSize: number
  /** 外层动画区域尺寸。 */
  frameSize: number
}

const SIZE_TOKENS: Record<NonNullable<BrandLoadingBlockProps['size']>, {
  markSize: number
  haloSize: number
  gap: number
  titleSize: number
  subtitleSize: number
  minHeight: number
}> = {
  sm: {
    markSize: 42,
    haloSize: 80,
    gap: 14,
    titleSize: 13,
    subtitleSize: 11,
    minHeight: 136,
  },
  md: {
    markSize: 56,
    haloSize: 104,
    gap: 16,
    titleSize: 14,
    subtitleSize: 12,
    minHeight: 184,
  },
  lg: {
    markSize: 72,
    haloSize: 132,
    gap: 18,
    titleSize: 15,
    subtitleSize: 12,
    minHeight: 228,
  },
}

function BrandAnimationStyles() {
  return (
    <style>{`
      @keyframes brand-loading-mark {
        0% { transform: translateY(0); box-shadow: 0 16px 36px rgba(65, 209, 156, 0.22); }
        50% { transform: translateY(-2px); box-shadow: 0 20px 40px rgba(65, 209, 156, 0.26); }
        100% { transform: translateY(0); box-shadow: 0 16px 36px rgba(65, 209, 156, 0.22); }
      }

      @keyframes brand-loading-mark-tilt {
        0%, 100% { rotate: -4deg; }
        25% { rotate: -2deg; }
        50% { rotate: 2deg; }
        75% { rotate: -1deg; }
      }

      @keyframes brand-loading-mark-glimmer {
        0% { background-position: 0% 50%; }
        100% { background-position: 100% 50%; }
      }

      @keyframes brand-loading-halo {
        0%, 100% { transform: scale(0.96); opacity: 0.4; }
        50% { transform: scale(1.03); opacity: 0.68; }
      }

      @keyframes brand-loading-aura {
        0%, 100% { transform: scale(0.95); opacity: 0.18; filter: blur(18px); }
        50% { transform: scale(1.08); opacity: 0.42; filter: blur(24px); }
      }

      @keyframes brand-loading-core-glow {
        0% { transform: scale(0.9) rotate(0deg); opacity: 0.16; }
        50% { transform: scale(1.02) rotate(180deg); opacity: 0.34; }
        100% { transform: scale(0.9) rotate(360deg); opacity: 0.16; }
      }

      @keyframes brand-loading-core-flare {
        0%, 100% { transform: scaleX(0.9) scaleY(0.92); opacity: 0.12; }
        50% { transform: scaleX(1.06) scaleY(1.02); opacity: 0.28; }
      }

      @keyframes brand-loading-scan {
        0% { transform: translate3d(-125%, -50%, 0) rotate(22deg); opacity: 0; }
        26% { opacity: 0.22; }
        52% { opacity: 0.38; }
        100% { transform: translate3d(125%, -50%, 0) rotate(22deg); opacity: 0; }
      }

      @keyframes brand-loading-orbit {
        0% { transform: rotate(0deg); }
        100% { transform: rotate(360deg); }
      }

      @keyframes brand-loading-orbit-dot {
        0%, 100% { transform: scale(0.74); opacity: 0.34; }
        50% { transform: scale(1); opacity: 0.82; }
      }

      @keyframes brand-loading-flash {
        0%, 100% { opacity: 0; transform: scale(0.84); }
        34% { opacity: 0.08; }
        52% { opacity: 0.34; transform: scale(1.02); }
        100% { opacity: 0; transform: scale(1.1); }
      }

      @keyframes brand-loading-starburst {
        0%, 100% { opacity: 0.1; transform: rotate(0deg) scale(0.98); }
        50% { opacity: 0.4; transform: rotate(90deg) scale(1.05); }
      }

      @keyframes brand-ambient-shell {
        0%, 100% { transform: translateY(0) scale(0.985); }
        50% { transform: translateY(-3px) scale(1.015); }
      }

      @keyframes brand-ambient-halo {
        0%, 100% { transform: scale(0.96); opacity: 0.36; }
        50% { transform: scale(1.04); opacity: 0.62; }
      }

      @keyframes brand-ambient-ring {
        0%, 100% { transform: scale(0.98); opacity: 0.54; }
        50% { transform: scale(1.02); opacity: 0.8; }
      }

      @keyframes brand-ambient-orbit {
        0% { transform: rotate(0deg); }
        100% { transform: rotate(360deg); }
      }

      @keyframes brand-ambient-dot {
        0%, 100% { transform: scale(0.82); opacity: 0.52; }
        50% { transform: scale(1.08); opacity: 0.92; }
      }

      @keyframes brand-ambient-mark {
        0%, 100% { transform: translateY(0); }
        50% { transform: translateY(-1px); }
      }

      @keyframes brand-loading-pulse-ring {
        0% { transform: scale(0.74); opacity: 0; }
        24% { opacity: 0.2; }
        66% { opacity: 0.06; }
        100% { transform: scale(1.16); opacity: 0; }
      }

      @keyframes brand-loading-phase-aura {
        0%, 4% { opacity: 0; }
        8%, 24% { opacity: 1; }
        28%, 100% { opacity: 0; }
      }

      @keyframes brand-loading-phase-scan {
        0%, 28% { opacity: 0; }
        32%, 48% { opacity: 1; }
        52%, 100% { opacity: 0; }
      }

      @keyframes brand-loading-phase-orbit {
        0%, 52% { opacity: 0; }
        56%, 72% { opacity: 1; }
        76%, 100% { opacity: 0; }
      }

      @keyframes brand-loading-phase-flash {
        0%, 76% { opacity: 0; }
        80%, 92% { opacity: 1; }
        100% { opacity: 0; }
      }

      @keyframes brand-spinner-rise {
        0%, 100% { transform: translateY(0) scale(0.98); }
        50% { transform: translateY(-4px) scale(1.02); }
      }

      @keyframes brand-spinner-glow {
        0% { background-position: 0% 50%; box-shadow: 0 14px 28px rgba(65, 209, 156, 0.18); }
        50% { background-position: 100% 50%; box-shadow: 0 18px 36px rgba(72, 167, 255, 0.24); }
        100% { background-position: 0% 50%; box-shadow: 0 14px 28px rgba(65, 209, 156, 0.18); }
      }

      @keyframes brand-spinner-ring {
        0% { transform: scale(0.72); opacity: 0; }
        28% { opacity: 0.28; }
        100% { transform: scale(1.12); opacity: 0; }
      }

      @keyframes brand-spinner-sweep {
        0% { transform: translate3d(-140%, -50%, 0) rotate(18deg); opacity: 0; }
        24% { opacity: 0.18; }
        48% { opacity: 0.34; }
        100% { transform: translate3d(140%, -50%, 0) rotate(18deg); opacity: 0; }
      }

      @keyframes brand-spinner-orbit {
        0% { transform: rotate(0deg); }
        100% { transform: rotate(360deg); }
      }

      @keyframes brand-spinner-dot {
        0%, 100% { transform: scale(0.78); opacity: 0.4; }
        50% { transform: scale(1.08); opacity: 0.92; }
      }

      /* 极简角标：N 字块静止，一道柔光周期性斜扫而过（不呼吸） */
      @keyframes brand-seal-min-sheen {
        0% { transform: translate3d(-140%, -16%, 0) rotate(14deg); opacity: 0; }
        16% { opacity: 0.55; }
        40% { opacity: 0.55; }
        58%, 100% { transform: translate3d(150%, 16%, 0) rotate(14deg); opacity: 0; }
      }

      @media (prefers-reduced-motion: reduce) {
        .brand-seal-ambient *,
        .brand-seal-ambient,
        .brand-seal-min *,
        .brand-seal-min {
          animation: none !important;
        }
      }
    `}</style>
  )
}

/**
 * 品牌 N 色块。
 * 作为大小 loading 的共同视觉核心。
 */
export function BrandMark({ size, animated = true }: BrandMarkProps) {
  const markRadius = size < 24 ? Math.max(4, Math.round(size * 0.28)) : Math.max(12, Math.round(size * 0.29))
  const markFontSize = size < 24 ? Math.max(10, Math.round(size * 0.54)) : Math.max(18, Math.round(size * 0.54))

  return (
    <span
      aria-hidden="true"
      style={{
        width: size,
        height: size,
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        borderRadius: markRadius,
        background: 'linear-gradient(135deg, var(--accent-blue), var(--accent-green))',
        color: '#061014',
        fontSize: markFontSize,
        fontWeight: 800,
        lineHeight: 1,
        boxShadow: '0 16px 36px rgba(65, 209, 156, 0.22)',
        animation: animated
          ? 'brand-loading-mark 3s ease-in-out infinite, brand-loading-mark-tilt 7.6s ease-in-out infinite'
          : undefined,
        transformOrigin: '50% 58%',
      }}
    >
      N
    </span>
  )
}

/**
 * 带光晕与轨道动画的品牌加载徽章。
 * 适合大 loading 和强调型等待反馈。
 */
export function AnimatedBrandSeal({
  markSize,
  haloSize,
  intensity = 'normal',
  variant = 'seal',
}: AnimatedBrandSealProps) {
  if (variant === 'minimal') {
    return (
      <>
        <BrandAnimationStyles />
        <div
          className="brand-seal-min"
          aria-hidden="true"
          style={{
            position: 'relative',
            width: haloSize,
            height: haloSize,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            borderRadius: Math.max(14, Math.round(haloSize * 0.3)),
          }}
        >
          <span
            style={{
              position: 'absolute',
              inset: 0,
              borderRadius: 'inherit',
              background: 'radial-gradient(circle, color-mix(in srgb, var(--accent-green) 16%, transparent) 0%, color-mix(in srgb, var(--accent-blue) 10%, transparent) 55%, transparent 78%)',
              pointerEvents: 'none',
            }}
          />
          <span
            style={{
              position: 'absolute',
              inset: 1,
              borderRadius: 'inherit',
              border: '1px solid color-mix(in srgb, var(--accent-blue) 16%, transparent)',
              pointerEvents: 'none',
            }}
          />
          <span
            style={{
              position: 'relative',
              width: markSize,
              height: markSize,
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              borderRadius: Math.max(10, Math.round(markSize * 0.28)),
              background: 'linear-gradient(135deg, var(--accent-blue), var(--accent-green))',
              color: '#061014',
              fontSize: Math.max(14, Math.round(markSize * 0.54)),
              fontWeight: 800,
              lineHeight: 1,
              boxShadow: '0 6px 14px rgba(65, 209, 156, 0.18)',
              overflow: 'hidden',
            }}
          >
            N
            <span
              style={{
                position: 'absolute',
                left: '-30%',
                top: 0,
                width: '42%',
                height: '100%',
                background: 'linear-gradient(105deg, transparent, rgba(255, 255, 255, 0.6), transparent)',
                transform: 'translate3d(-140%, -16%, 0) rotate(14deg)',
                filter: 'blur(1px)',
                animation: 'brand-seal-min-sheen 3.6s ease-in-out infinite',
                pointerEvents: 'none',
              }}
            />
          </span>
        </div>
      </>
    )
  }

  if (variant === 'ambient') {
    const isStrong = intensity === 'strong'
    const shellRadius = Math.max(24, Math.round(haloSize * 0.34))
    const ringInset = Math.max(10, Math.round(haloSize * 0.11))
    const innerOrbitInset = Math.max(24, Math.round(haloSize * 0.22))
    const dotSize = isStrong ? 10 : 8

    return (
      <>
        <BrandAnimationStyles />
        <div
          className="brand-seal-ambient"
          aria-hidden="true"
          style={{
            position: 'relative',
            width: haloSize,
            height: haloSize,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            borderRadius: shellRadius,
            background: 'color-mix(in srgb, var(--accent-blue-dim) 24%, transparent)',
            boxShadow: isStrong
              ? 'inset 0 0 0 1px color-mix(in srgb, var(--accent-blue) 16%, transparent), 0 18px 48px rgba(0, 0, 0, 0.14)'
              : 'inset 0 0 0 1px color-mix(in srgb, var(--accent-blue) 12%, transparent)',
            overflow: 'hidden',
            animation: `brand-ambient-shell ${isStrong ? '6.2s' : '7.4s'} ease-in-out infinite`,
          }}
        >
          <span
            style={{
              position: 'absolute',
              inset: Math.max(-8, -Math.round(haloSize * 0.06)),
              borderRadius: Math.max(28, Math.round(haloSize * 0.42)),
              background: 'radial-gradient(circle, rgba(65, 209, 156, 0.18) 0%, rgba(72, 167, 255, 0.12) 44%, transparent 72%)',
              opacity: 0.5,
              animation: `brand-ambient-halo ${isStrong ? '5.6s' : '6.6s'} ease-in-out infinite`,
            }}
          />
          <span
            style={{
              position: 'absolute',
              inset: 8,
              borderRadius: Math.max(18, Math.round(haloSize * 0.26)),
              border: '1px solid color-mix(in srgb, var(--accent-green) 20%, transparent)',
              animation: `brand-ambient-ring ${isStrong ? '4.8s' : '5.6s'} ease-in-out infinite`,
            }}
          />
          <span
            style={{
              position: 'absolute',
              inset: ringInset,
              animation: `brand-ambient-orbit ${isStrong ? '11.8s' : '13.2s'} linear infinite`,
            }}
          >
            <span
              style={{
                position: 'absolute',
                top: -dotSize / 2,
                left: '50%',
                width: dotSize,
                height: dotSize,
                marginLeft: -dotSize / 2,
                borderRadius: 999,
                background: 'color-mix(in srgb, var(--accent-green) 82%, white)',
                boxShadow: '0 0 8px rgba(65, 209, 156, 0.34)',
                animation: `brand-ambient-dot ${isStrong ? '3s' : '3.6s'} ease-in-out infinite`,
              }}
            />
          </span>
          <span
            style={{
              position: 'absolute',
              inset: innerOrbitInset,
              animation: `brand-ambient-orbit ${isStrong ? '8.8s' : '10.4s'} linear reverse infinite`,
              opacity: 0.9,
            }}
          >
            <span
              style={{
                position: 'absolute',
                right: -dotSize / 2,
                top: '50%',
                width: Math.max(6, dotSize - 2),
                height: Math.max(6, dotSize - 2),
                marginTop: -Math.max(3, (dotSize - 2) / 2),
                borderRadius: 999,
                background: 'color-mix(in srgb, var(--accent-blue) 86%, white)',
                boxShadow: '0 0 8px rgba(72, 167, 255, 0.28)',
                animation: `brand-ambient-dot ${isStrong ? '2.8s' : '3.4s'} ease-in-out 0.3s infinite`,
              }}
            />
          </span>
          <span
            style={{
              position: 'relative',
              display: 'inline-flex',
              padding: 2,
              borderRadius: Math.max(14, Math.round(markSize * 0.32)),
              background: 'linear-gradient(120deg, rgba(255,255,255,0.08), rgba(255,255,255,0.24), rgba(255,255,255,0.08))',
              boxShadow: '0 0 16px rgba(255,255,255,0.1)',
            }}
          >
            <span
              style={{
                display: 'inline-flex',
                animation: `brand-ambient-mark ${isStrong ? '4.2s' : '4.8s'} ease-in-out infinite`,
              }}
            >
              <BrandMark size={markSize} />
            </span>
          </span>
        </div>
      </>
    )
  }

  const isStrong = intensity === 'strong'
  const phaseDuration = isStrong ? '16.8s' : '18.4s'
  const outerInset = Math.max(2, Math.round(haloSize * 0.05))
  const orbitInset = Math.max(10, Math.round(haloSize * 0.11))
  const orbitInnerInset = Math.max(16, Math.round(haloSize * 0.18))

  return (
    <>
      <BrandAnimationStyles />
      <div
        aria-hidden="true"
        style={{
          position: 'relative',
          width: haloSize,
          height: haloSize,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          borderRadius: Math.max(24, Math.round(haloSize * 0.34)),
          background: 'color-mix(in srgb, var(--accent-blue-dim) 26%, transparent)',
          boxShadow: isStrong
            ? 'inset 0 0 0 1px color-mix(in srgb, var(--accent-blue) 18%, transparent), 0 20px 56px rgba(0, 0, 0, 0.18)'
            : 'inset 0 0 0 1px color-mix(in srgb, var(--accent-blue) 14%, transparent)',
          overflow: 'hidden',
        }}
      >
        <span
          style={{
            position: 'absolute',
            inset: 0,
            animation: `brand-loading-phase-aura ${phaseDuration} linear infinite`,
            pointerEvents: 'none',
          }}
        >
          <span
            style={{
              position: 'absolute',
              inset: Math.max(-10, -Math.round(haloSize * 0.08)),
              borderRadius: Math.max(28, Math.round(haloSize * 0.42)),
              background: 'radial-gradient(circle, rgba(65, 209, 156, 0.24) 0%, rgba(72, 167, 255, 0.14) 42%, transparent 72%)',
            animation: `brand-loading-aura ${isStrong ? '5.8s' : '6.6s'} ease-in-out infinite`,
            }}
          />
          <span
            style={{
              position: 'absolute',
              inset: Math.max(6, Math.round(haloSize * 0.08)),
              borderRadius: Math.max(18, Math.round(haloSize * 0.24)),
              background: 'conic-gradient(from 0deg, transparent 0deg, rgba(72,167,255,0.08) 54deg, rgba(65,209,156,0.24) 136deg, rgba(255,255,255,0.18) 190deg, rgba(72,167,255,0.12) 286deg, transparent 360deg)',
              animation: `brand-loading-core-glow ${isStrong ? '8.2s' : '9.2s'} linear infinite`,
            }}
          />
          <span
            style={{
              position: 'absolute',
              inset: Math.max(18, Math.round(haloSize * 0.2)),
              borderRadius: 999,
              background: 'radial-gradient(circle, rgba(255,255,255,0.16) 0%, rgba(72,167,255,0.1) 38%, transparent 72%)',
              animation: `brand-loading-core-flare ${isStrong ? '4.8s' : '5.4s'} ease-in-out infinite`,
            }}
          />
          <span
            style={{
              position: 'absolute',
              inset: outerInset,
              borderRadius: Math.max(22, Math.round(haloSize * 0.32)),
              border: '1px solid color-mix(in srgb, var(--accent-blue) 20%, transparent)',
              animation: `brand-loading-pulse-ring ${isStrong ? '4.2s' : '4.8s'} ease-out infinite`,
            }}
          />
          <span
            style={{
              position: 'absolute',
              inset: outerInset,
              borderRadius: Math.max(22, Math.round(haloSize * 0.32)),
              border: '1px solid color-mix(in srgb, var(--accent-green) 22%, transparent)',
              animation: `brand-loading-pulse-ring ${isStrong ? '4.2s' : '4.8s'} ease-out ${isStrong ? '2.1s' : '2.4s'} infinite`,
            }}
          />
        </span>

        <span
          style={{
            position: 'absolute',
            inset: 8,
            borderRadius: Math.max(18, Math.round(haloSize * 0.26)),
            border: '1px solid color-mix(in srgb, var(--accent-green) 22%, transparent)',
            opacity: 0.64,
            animation: `brand-loading-halo ${isStrong ? '4.2s' : '4.8s'} ease-in-out infinite`,
          }}
        />

        <span
          style={{
            position: 'absolute',
            inset: 0,
            animation: `brand-loading-phase-scan ${phaseDuration} linear infinite`,
            pointerEvents: 'none',
          }}
        >
          <span
            style={{
              position: 'absolute',
              left: '-20%',
              top: '50%',
              width: '42%',
              height: '160%',
              background: 'linear-gradient(180deg, transparent, rgba(255,255,255,0.46), transparent)',
              transform: 'translate3d(-125%, -50%, 0) rotate(22deg)',
              filter: 'blur(2px)',
              animation: `brand-loading-scan ${isStrong ? '4.6s' : '5.2s'} linear infinite`,
            }}
          />
        </span>

        <span
          style={{
            position: 'absolute',
            inset: 0,
            animation: `brand-loading-phase-orbit ${phaseDuration} linear infinite`,
            pointerEvents: 'none',
          }}
        >
          <span
            style={{
              position: 'absolute',
              inset: orbitInset,
              animation: `brand-loading-orbit ${isStrong ? '9.2s' : '10.4s'} linear infinite`,
            }}
          >
            <span
              style={{
                position: 'absolute',
                top: -2,
                left: '50%',
                width: isStrong ? 12 : 10,
                height: isStrong ? 12 : 10,
                marginLeft: isStrong ? -6 : -5,
                borderRadius: 999,
                background: 'color-mix(in srgb, var(--accent-green) 82%, white)',
                boxShadow: '0 0 12px rgba(65, 209, 156, 0.56)',
                animation: `brand-loading-orbit-dot ${isStrong ? '3.2s' : '3.8s'} ease-in-out infinite`,
              }}
            />
          </span>
          <span
            style={{
              position: 'absolute',
              inset: orbitInnerInset,
              animation: `brand-loading-orbit ${isStrong ? '7.2s' : '8.4s'} linear reverse infinite`,
            }}
          >
            <span
              style={{
                position: 'absolute',
                right: -2,
                top: '50%',
                width: isStrong ? 10 : 8,
                height: isStrong ? 10 : 8,
                marginTop: isStrong ? -5 : -4,
                borderRadius: 999,
                background: 'color-mix(in srgb, var(--accent-blue) 88%, white)',
                boxShadow: '0 0 10px rgba(72, 167, 255, 0.48)',
                animation: `brand-loading-orbit-dot ${isStrong ? '2.9s' : '3.5s'} ease-in-out 0.25s infinite`,
              }}
            />
          </span>
        </span>

        <span
          style={{
            position: 'absolute',
            inset: 0,
            animation: `brand-loading-phase-flash ${phaseDuration} linear infinite`,
            pointerEvents: 'none',
          }}
        >
          <span
            style={{
              position: 'absolute',
              inset: Math.max(18, Math.round(haloSize * 0.18)),
              borderRadius: 999,
              background: 'radial-gradient(circle, rgba(255,255,255,0.42) 0%, rgba(65,209,156,0.14) 24%, rgba(72,167,255,0.08) 42%, transparent 70%)',
              animation: `brand-loading-flash ${isStrong ? '4.2s' : '4.8s'} ease-out infinite`,
              mixBlendMode: 'screen',
            }}
          />
          <span
            style={{
              position: 'absolute',
              inset: Math.max(8, Math.round(haloSize * 0.1)),
              background: 'linear-gradient(90deg, transparent 0%, rgba(255,255,255,0.12) 46%, rgba(255,255,255,0.5) 50%, rgba(255,255,255,0.12) 54%, transparent 100%)',
              clipPath: 'polygon(50% 0%, 56% 38%, 100% 50%, 56% 62%, 50% 100%, 44% 62%, 0% 50%, 44% 38%)',
              filter: 'blur(1px)',
              animation: `brand-loading-starburst ${isStrong ? '4s' : '4.6s'} ease-in-out infinite`,
              mixBlendMode: 'screen',
            }}
          />
        </span>

        <span
          style={{
            position: 'relative',
            display: 'inline-flex',
            borderRadius: Math.max(12, Math.round(markSize * 0.29)),
            background: 'linear-gradient(120deg, rgba(255,255,255,0.06), rgba(255,255,255,0.28), rgba(255,255,255,0.06))',
            backgroundSize: '200% 200%',
            padding: 2,
            animation: `brand-loading-mark-glimmer ${isStrong ? '4.2s' : '4.8s'} linear infinite`,
            boxShadow: isStrong ? '0 0 22px rgba(255,255,255,0.14)' : '0 0 18px rgba(255,255,255,0.12)',
          }}
        >
          <BrandMark size={markSize} />
        </span>
      </div>
    </>
  )
}

/**
 * Loading 专用品牌动画。
 * 与空白页的大徽章动效分离，节奏更快，强调“正在处理中”的反馈。
 */
function LoadingBrandSpinner({ markSize, frameSize }: LoadingBrandSpinnerProps) {
  const ringInset = Math.max(8, Math.round(frameSize * 0.11))
  const innerInset = Math.max(18, Math.round(frameSize * 0.22))
  const dotSize = Math.max(8, Math.round(markSize * 0.16))

  return (
    <>
      <BrandAnimationStyles />
      <div
        aria-hidden="true"
        style={{
          position: 'relative',
          width: frameSize,
          height: frameSize,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          borderRadius: Math.max(24, Math.round(frameSize * 0.34)),
          background: 'radial-gradient(circle, rgba(72, 167, 255, 0.08) 0%, rgba(65, 209, 156, 0.1) 42%, transparent 74%)',
          overflow: 'hidden',
        }}
      >
        <span
          style={{
            position: 'absolute',
            inset: ringInset,
            borderRadius: Math.max(20, Math.round(frameSize * 0.28)),
            border: '1px solid color-mix(in srgb, var(--accent-blue) 28%, transparent)',
            animation: 'brand-spinner-ring 1.08s ease-out infinite',
          }}
        />
        <span
          style={{
            position: 'absolute',
            inset: ringInset,
            borderRadius: Math.max(20, Math.round(frameSize * 0.28)),
            border: '1px solid color-mix(in srgb, var(--accent-green) 30%, transparent)',
            animation: 'brand-spinner-ring 1.08s ease-out 0.36s infinite',
          }}
        />
        <span
          style={{
            position: 'absolute',
            inset: 0,
            pointerEvents: 'none',
          }}
        >
          <span
            style={{
              position: 'absolute',
              left: '-24%',
              top: '50%',
              width: '38%',
              height: '150%',
              background: 'linear-gradient(180deg, transparent, rgba(255,255,255,0.42), transparent)',
              filter: 'blur(2px)',
              animation: 'brand-spinner-sweep 0.96s linear infinite',
            }}
          />
        </span>
        <span
          style={{
            position: 'absolute',
            inset: innerInset,
            animation: 'brand-spinner-orbit 1.28s linear infinite',
            pointerEvents: 'none',
          }}
        >
          <span
            style={{
              position: 'absolute',
              top: -2,
              left: '50%',
              width: dotSize,
              height: dotSize,
              marginLeft: -dotSize / 2,
              borderRadius: 999,
              background: 'color-mix(in srgb, var(--accent-green) 82%, white)',
              boxShadow: '0 0 12px rgba(65, 209, 156, 0.52)',
              animation: 'brand-spinner-dot 0.72s ease-in-out infinite',
            }}
          />
        </span>
        <span
          style={{
            position: 'relative',
            display: 'inline-flex',
            alignItems: 'center',
            justifyContent: 'center',
            width: markSize,
            height: markSize,
            borderRadius: Math.max(12, Math.round(markSize * 0.29)),
            background: 'linear-gradient(135deg, var(--accent-blue), var(--accent-green), var(--accent-blue))',
            backgroundSize: '200% 200%',
            color: '#061014',
            fontSize: Math.max(18, Math.round(markSize * 0.54)),
            fontWeight: 800,
            lineHeight: 1,
            animation: 'brand-spinner-rise 0.9s ease-in-out infinite, brand-spinner-glow 1.02s linear infinite',
            boxShadow: '0 14px 28px rgba(65, 209, 156, 0.18)',
          }}
        >
          N
        </span>
      </div>
    </>
  )
}

/**
 * 通用大尺寸品牌加载组件。
 * 适合初始化、后台同步等长耗时操作，可独立展示或覆盖当前容器。
 */
export default function BrandLoadingBlock({
  title = '加载中...',
  subtitle,
  size = 'md',
  overlay = false,
  portalToBody = false,
  minHeight,
}: BrandLoadingBlockProps) {
  const tokens = SIZE_TOKENS[size]

  const content = (
    <div
      role="status"
      aria-live="polite"
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        gap: tokens.gap,
        textAlign: 'center',
        padding: overlay ? 24 : 20,
        width: '100%',
        maxWidth: overlay ? 320 : 420,
        margin: '0 auto',
        boxSizing: 'border-box',
      }}
    >
      <LoadingBrandSpinner markSize={tokens.markSize} frameSize={tokens.haloSize} />
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6, alignItems: 'center' }}>
        <div
          style={{
            fontSize: tokens.titleSize,
            fontWeight: 700,
            color: 'var(--text-primary)',
            letterSpacing: 0.2,
          }}
        >
          {title}
        </div>
        {subtitle ? (
          <div
            style={{
              maxWidth: 320,
              fontSize: tokens.subtitleSize,
              lineHeight: 1.7,
              color: 'var(--text-muted)',
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-word',
            }}
          >
            {subtitle}
          </div>
        ) : null}
      </div>
    </div>
  )

  if (overlay) {
    const overlayNode = (
      <div
        style={{
          position: portalToBody ? 'fixed' : 'absolute',
          inset: 0,
          zIndex: portalToBody ? 120 : 24,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          padding: 20,
          background: 'color-mix(in srgb, var(--bg-primary) 34%, transparent)',
          backdropFilter: 'blur(6px)',
          boxSizing: 'border-box',
        }}
      >
        <div
          style={{
            width: 'min(100%, 360px)',
            margin: '0 auto',
            borderRadius: 'var(--radius-xl)',
            border: '1px solid color-mix(in srgb, var(--accent-blue) 16%, var(--border))',
            background: 'color-mix(in srgb, var(--bg-secondary) 82%, transparent)',
            boxShadow: '0 20px 54px rgba(0, 0, 0, 0.12)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            boxSizing: 'border-box',
          }}
        >
          {content}
        </div>
      </div>
    )

    if (portalToBody && typeof document !== 'undefined') {
      return createPortal(overlayNode, document.body)
    }

    return overlayNode
  }

  return (
    <div
      style={{
        flex: 1,
        width: '100%',
        minHeight: minHeight ?? tokens.minHeight,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: 16,
        boxSizing: 'border-box',
      }}
    >
      {content}
    </div>
  )
}
