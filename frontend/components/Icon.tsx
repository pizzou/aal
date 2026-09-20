"use client";
import React from "react";
export type IconName =
  | "grid"
  | "ship"
  | "plane"
  | "truck"
  | "warehouse"
  | "chart"
  | "file"
  | "search"
  | "bell"
  | "plus"
  | "arrow"
  | "shield"
  | "spark"
  | "globe"
  | "money"
  | "calendar"
  | "check"
  | "train"
  | "menu"
  | "logout"
  | "chevron";
export default function Icon({
  name,
  size = 18,
}: {
  name: IconName;
  size?: number;
}) {
  const p = {
    width: size,
    height: size,
    viewBox: "0 0 24 24",
    fill: "none",
    stroke: "currentColor",
    strokeWidth: 1.8,
    strokeLinecap: "round" as const,
    strokeLinejoin: "round" as const,
    ariaHidden: true,
  };
  const x: Record<IconName, React.ReactNode> = {
    grid: (
      <>
        <rect x="3" y="3" width="7" height="7" rx="1" />
        <rect x="14" y="3" width="7" height="7" rx="1" />
        <rect x="3" y="14" width="7" height="7" rx="1" />
        <rect x="14" y="14" width="7" height="7" rx="1" />
      </>
    ),
    ship: (
      <>
        <path d="M3 17h18l-2 3H5l-2-3Z" />
        <path d="M6 17V7h8l4 4v6" />
        <path d="M9 7V4h4v3" />
      </>
    ),
    plane: (
      <path d="M3 11h7l3-8 2 1-1 7h7c1.3 0 2.2 1.5 1.4 2.5-.3.3-.8.5-1.4.5h-7l1 7-2 1-3-8H3l-1-1 1-1Z" />
    ),
    truck: (
      <>
        <path d="M3 6h11v10H3z" />
        <path d="M14 10h4l3 3v3h-7z" />
        <circle cx="7" cy="18" r="2" />
        <circle cx="18" cy="18" r="2" />
      </>
    ),
    warehouse: (
      <>
        <path d="M3 10 12 4l9 6v10H3z" />
        <path d="M7 20v-6h10v6M7 10h10" />
      </>
    ),
    chart: (
      <>
        <path d="M4 19V5M4 19h17" />
        <path d="m7 15 4-5 3 3 5-7" />
      </>
    ),
    file: (
      <>
        <path d="M6 3h8l4 4v14H6z" />
        <path d="M14 3v5h5M9 13h6M9 17h6" />
      </>
    ),
    search: (
      <>
        <circle cx="11" cy="11" r="6.5" />
        <path d="m16 16 5 5" />
      </>
    ),
    bell: (
      <>
        <path d="M18 9a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9" />
        <path d="M10 21h4" />
      </>
    ),
    plus: (
      <>
        <path d="M12 5v14M5 12h14" />
      </>
    ),
    arrow: (
      <>
        <path d="M5 12h13" />
        <path d="m13 6 6 6-6 6" />
      </>
    ),
    shield: (
      <>
        <path d="M12 3 20 6v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z" />
        <path d="m8 12 2.5 2.5L16 9" />
      </>
    ),
    spark: (
      <>
        <path d="m12 2 1.7 6.3L20 10l-6.3 1.7L12 18l-1.7-6.3L4 10l6.3-1.7z" />
        <path d="m19 16 .7 2.3L22 19l-2.3.7L19 22l-.7-2.3L16 19l2.3-.7z" />
      </>
    ),
    globe: (
      <>
        <circle cx="12" cy="12" r="9" />
        <path d="M3 12h18M12 3c2.5 2.5 3.5 5.5 3.5 9s-1 6.5-3.5 9c-2.5-2.5-3.5-5.5-3.5-9S9.5 5.5 12 3Z" />
      </>
    ),
    money: (
      <>
        <rect x="3" y="5" width="18" height="14" rx="2" />
        <circle cx="12" cy="12" r="3" />
      </>
    ),
    calendar: (
      <>
        <rect x="3" y="5" width="18" height="16" rx="2" />
        <path d="M16 3v4M8 3v4M3 10h18" />
      </>
    ),
    check: <path d="m5 12 4 4L19 6" />,
    train: (
      <>
        <rect x="6" y="3" width="12" height="15" rx="2" />
        <path d="M6 13h12M9 18l-2 3M15 18l2 3M9 8h.01M15 8h.01" />
      </>
    ),
    menu: (
      <>
        <path d="M4 7h16M4 12h16M4 17h16" />
      </>
    ),
    logout: (
      <>
        <path d="M10 5H5v14h5M14 8l4 4-4 4M18 12H9" />
      </>
    ),
    chevron: <path d="m9 18 6-6-6-6" />,
  };
  return <svg {...p}>{x[name]}</svg>;
}
