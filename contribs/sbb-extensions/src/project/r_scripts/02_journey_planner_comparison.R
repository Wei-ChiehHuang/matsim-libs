# 02_journey_planner_comparison.R
#
# Compares the segmented PT skims with a journey planner (e.g. MVV, bahn.de, Google Maps), in two steps:
#
#   step <- "prepare"  selects OD pairs and writes a template CSV with coordinates (WGS84), links and the skim
#                      values. One row per OD pair and skim, because each skim should be compared with a planner
#                      query restricted to the same modes:
#                        bus  -> only bus (and ferry)
#                        btm  -> bus + tram + U-Bahn
#                        all  -> all modes (incl. S-Bahn, regional trains)
#                      (MVV / bahn.de: "Verkehrsmittel" / "means of transport" filter.)
#                      Rows where the skim is Infinity are kept; the planner should then find no trip with the
#                      main mode (bus-only trips in "btm"/"all" count as "no trip" for that skim).
#   -> fill in the jp_* columns by hand (see "how to fill in" below).
#   step <- "compare"  reads the filled template and compares skim vs planner (table, plots, flagged rows).
#
# How to fill in (per row, weekday, departures between 07:30 and 08:30):
#   jp_found               yes / no  (no = the planner finds no trip that uses the skim's main mode)
#   jp_total_min           door-to-door time from the first walk to arrival (without waiting at home).
#                          Ideally the average over all trips departing 07:30-08:30, otherwise a typical trip.
#   jp_walk_access_min     walk to the first stop
#   jp_walk_egress_min     walk from the last stop
#   jp_ivt_min             time in vehicles (sum of all legs)
#   jp_main_ivt_min        time in vehicles of the main mode (bus skim: = jp_ivt_min)
#   jp_transfer_min        time for transfers (walking + waiting between legs)
#   jp_transfers           number of transfers
#   jp_trips_per_hour      number of useful departures between 07:30 and 08:30
#   jp_modes               e.g. "Bus 210 > U2 > Bus 54"
#   jp_notes               anything unusual
#
# Expected differences (not errors):
#   - walking: the skim uses straight-line distance x 1.3 at 3 km/h, planners use a street network at ~4.5-5 km/h,
#     so skim walk times are usually LONGER.
#   - the skim averages 3 x 3 sampling points per zone; the template uses the mean of the 3 points per zone.
#   - the skim averages the whole hour (rooftop shares); a single planner trip can be better or worse.
#   - waiting at the first stop is excluded on both sides (in the skim it is in pt_adaptiontime).

suppressPackageStartupMessages({
  library(data.table)
  library(sf)
  library(ggplot2)
})

# ------------------------------------------------------------------ settings
r_script_dir <- "C:/models/matsim-libs/contribs/sbb-extensions/src/project/r_scripts"
skim_dir <- "C:/models/matsim-libs/contribs/sbb-extensions/src/project/output/skims_muc_test"
sampling_points_file <- "C:/models/matsim-libs/contribs/sbb-extensions/src/project/input/zone_coordinates_muc_5452_test.csv"
crs_skims <- 31468                 # DHDN / Gauss-Kruger zone 4
template_file <- file.path(skim_dir, "validation", "journey_planner_template.csv")
filled_file <- template_file       # the template after filling in (can be a copy with another name)

step <- "prepare"                  # "prepare" or "compare"

# OD selection for "prepare":
od_pairs <- NULL                   # e.g. data.frame(from = c("2641", "517"), to = c("3084", "4405")); NULL = automatic
n_per_band <- 2                    # automatic selection: interzonal pairs per distance band
skims_to_compare <- c("bus", "btm", "all")
seed <- 4711

# tolerances for flagging in "compare"
tol_total_rel <- 0.25              # |skim - planner| / planner
tol_total_abs_min <- 10            # and at least this many minutes
# ------------------------------------------------------------------

source(file.path(r_script_dir, "00_skim_utils.R"))
options(width = 220)
val_dir <- dir_create(file.path(skim_dir, "validation"))

skim_columns <- c("total_travel_time", "walk_access_time", "walk_egress_time", "pt_access_ivt", "pt_main_ivt", "pt_egress_ivt",
                  "pt_ivt_total", "pt_transfer_walk_time", "pt_transfer_wait_time", "pt_transfer_count",
                  "main_share_time", "main_mode_availability", "pt_frequency")

if (step == "prepare") {
  if (file.exists(template_file)) {
    stop("template exists already, will not overwrite filled-in values: ", template_file,
         "\nDelete or rename it to create a new one.")
  }

  base <- add_distance_band(load_base(skim_dir))

  # --- zone points: mean of the sampling points, in WGS84
  pts <- fread(sampling_points_file, sep = ";", colClasses = list(character = "ZONE"))
  zone_xy <- pts[, .(x = mean(X), y = mean(Y)), by = ZONE]
  zone_sf <- st_transform(st_as_sf(zone_xy, coords = c("x", "y"), crs = crs_skims), 4326)
  ll <- st_coordinates(zone_sf)
  zone_ll <- data.table(zone = zone_xy$ZONE, lon = round(ll[, 1], 6), lat = round(ll[, 2], 6))

  # --- OD selection
  if (is.null(od_pairs)) {
    set.seed(seed)
    cand <- base[intrazonal == FALSE]
    sel <- cand[, .SD[sample(.N, min(.N, n_per_band))], by = dist_band][, .(from, to)]
  } else {
    sel <- as.data.table(od_pairs)[, .(from = as.character(from), to = as.character(to))]
  }
  sel <- merge(sel, base[, .(from, to, beeline, dist_band)], by = c("from", "to"))
  cat("selected", nrow(sel), "OD pairs\n")

  # --- skim values
  rows <- list()
  for (s in skims_to_compare) {
    d <- load_skim(skim_dir, s, base, indicators = skim_columns)
    d <- merge(sel[, .(from, to)], d, by = c("from", "to"))
    rows[[s]] <- d[, .(skim = s, from, to,
                       beeline_km = round(beeline / 1000, 2),
                       sk_found = ifelse(is.finite(total_travel_time), "yes", "no"),
                       sk_total_min = round(total_travel_time / 60, 1),
                       sk_walk_access_min = round(walk_access_time / 60, 1),
                       sk_walk_egress_min = round(walk_egress_time / 60, 1),
                       sk_ivt_min = round(pt_ivt_total / 60, 1),
                       sk_main_ivt_min = round(pt_main_ivt / 60, 1),
                       sk_transfer_min = round((pt_transfer_walk_time + pt_transfer_wait_time) / 60, 1),
                       sk_transfers = round(pt_transfer_count, 2),
                       sk_trips_per_hour = round(pt_frequency, 1),
                       sk_main_share = round(main_share_time, 2),
                       sk_availability = round(main_mode_availability, 2))]
  }
  tpl <- rbindlist(rows)
  tpl <- merge(tpl, zone_ll[, .(from = zone, from_lat = lat, from_lon = lon)], by = "from")
  tpl <- merge(tpl, zone_ll[, .(to = zone, to_lat = lat, to_lon = lon)], by = "to")
  tpl[, modes_to_allow := c(bus = "bus (+ferry) only", btm = "bus + tram + U-Bahn", all = "all modes")[skim]]
  tpl[, google_maps := sprintf("https://www.google.com/maps/dir/?api=1&origin=%.6f,%.6f&destination=%.6f,%.6f&travelmode=transit",
                               from_lat, from_lon, to_lat, to_lon)]
  # empty columns to fill in
  for (col in c("jp_found", "jp_total_min", "jp_walk_access_min", "jp_walk_egress_min", "jp_ivt_min", "jp_main_ivt_min",
                "jp_transfer_min", "jp_transfers", "jp_trips_per_hour", "jp_modes", "jp_notes")) {
    set(tpl, j = col, value = NA_character_)
  }
  setcolorder(tpl, c("from", "to", "skim", "modes_to_allow", "beeline_km", "from_lat", "from_lon", "to_lat", "to_lon"))
  setorder(tpl, from, to, skim)
  fwrite(tpl, template_file, sep = ";", na = "")
  cat("template written:", template_file, "\n")
  cat("Fill in the jp_* columns (see the header of this script), then set step <- \"compare\".\n")
}

if (step == "compare") {
  jp <- fread(filled_file, sep = ";", colClasses = list(character = c("from", "to")), na.strings = "")
  num <- c("jp_total_min", "jp_walk_access_min", "jp_walk_egress_min", "jp_ivt_min", "jp_main_ivt_min",
           "jp_transfer_min", "jp_transfers", "jp_trips_per_hour")
  for (col in num) set(jp, j = col, value = suppressWarnings(as.numeric(gsub(",", ".", jp[[col]]))))
  jp[, jp_found := tolower(trimws(as.character(jp_found)))]
  filled <- jp[!is.na(jp_found) & jp_found != ""]
  if (nrow(filled) == 0) stop("no filled rows (jp_found is empty everywhere): ", filled_file)
  cat(nrow(filled), "of", nrow(jp), "rows filled in\n")

  # --- 1. found / not found agreement
  filled[, found_agree := (sk_found == "yes") == (jp_found == "yes")]
  cat("\n==== trip found: skim vs planner\n")
  print(filled[, .N, by = .(skim, sk_found, jp_found)][order(skim, sk_found, jp_found)])

  # --- 2. component differences (only where both found a trip)
  both <- filled[sk_found == "yes" & jp_found == "yes"]
  comp <- c("total_min", "walk_access_min", "walk_egress_min", "ivt_min", "main_ivt_min", "transfer_min", "transfers", "trips_per_hour")
  for (cm in comp) {
    set(both, j = paste0("diff_", cm), value = both[[paste0("sk_", cm)]] - both[[paste0("jp_", cm)]])
  }
  both[, ratio_total := sk_total_min / jp_total_min]
  both[, flag := abs(diff_total_min) > tol_total_abs_min & abs(ratio_total - 1) > tol_total_rel]

  summ <- rbindlist(lapply(comp, function(cm) {
    both[, .(component = cm, n = sum(!is.na(get(paste0("diff_", cm)))),
             mean_diff = round(mean(get(paste0("diff_", cm)), na.rm = TRUE), 1),
             median_diff = round(median(get(paste0("diff_", cm)), na.rm = TRUE), 1),
             mean_abs_diff = round(mean(abs(get(paste0("diff_", cm))), na.rm = TRUE), 1)), by = skim]
  }))
  cat("\n==== skim - planner (minutes, transfers, trips/h); positive = skim higher\n")
  print(summ[order(skim, component)], nrows = 200)

  cat("\n==== total time ratio skim / planner\n")
  print(both[, .(n = .N, median_ratio = round(median(ratio_total, na.rm = TRUE), 2),
                 p10 = round(quantile(ratio_total, 0.1, na.rm = TRUE), 2), p90 = round(quantile(ratio_total, 0.9, na.rm = TRUE), 2),
                 flagged = sum(flag, na.rm = TRUE)), by = skim])

  cat(sprintf("\n==== flagged rows (|diff| > %d min and > %d %%)\n", tol_total_abs_min, round(tol_total_rel * 100)))
  print(both[flag == TRUE, .(from, to, skim, beeline_km, sk_total_min, jp_total_min, diff_total_min,
                             diff_walk_access_min, diff_walk_egress_min, diff_ivt_min, diff_transfer_min, jp_modes)])
  cat("\n==== found / not found disagreements\n")
  print(filled[found_agree == FALSE, .(from, to, skim, beeline_km, sk_found, jp_found, sk_availability, jp_modes, jp_notes)])

  fwrite(both, file.path(val_dir, "journey_planner_comparison.csv"), sep = ";")
  fwrite(summ, file.path(val_dir, "journey_planner_summary.csv"), sep = ";")

  # --- 3. plots
  lim <- range(c(both$sk_total_min, both$jp_total_min), na.rm = TRUE)
  p1 <- ggplot(both, aes(jp_total_min, sk_total_min, colour = skim)) +
    geom_abline(slope = 1, intercept = 0) +
    geom_abline(slope = 1 + tol_total_rel, intercept = 0, linetype = "dashed", colour = "grey50") +
    geom_abline(slope = 1 - tol_total_rel, intercept = 0, linetype = "dashed", colour = "grey50") +
    geom_point(size = 2.5) +
    coord_equal(xlim = lim, ylim = lim) +
    labs(x = "journey planner total time [min]", y = "skim total time [min]",
         title = "Door-to-door travel time: skim vs journey planner", subtitle = sprintf("dashed: +/- %d %%", round(tol_total_rel * 100))) +
    theme_bw()
  ggsave(file.path(val_dir, "jp_total_time_scatter.png"), p1, width = 7, height = 7, dpi = 150)

  long <- melt(both[, c("from", "to", "skim", paste0("diff_", c("walk_access_min", "walk_egress_min", "ivt_min", "transfer_min"))), with = FALSE],
               id.vars = c("from", "to", "skim"), variable.name = "component", value.name = "diff_min")
  long[, component := sub("diff_", "", component)]
  p2 <- ggplot(long, aes(component, diff_min, fill = skim)) +
    geom_hline(yintercept = 0) +
    geom_boxplot(outlier.shape = 21, na.rm = TRUE) +
    labs(x = NULL, y = "skim - planner [min]", title = "Differences by component") +
    theme_bw()
  ggsave(file.path(val_dir, "jp_component_differences.png"), p2, width = 8, height = 5, dpi = 150)

  cat("\nwritten to", val_dir, ": journey_planner_comparison.csv, journey_planner_summary.csv, jp_total_time_scatter.png, jp_component_differences.png\n")
}
