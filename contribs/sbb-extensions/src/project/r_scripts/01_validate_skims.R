# 01_validate_skims.R
#
# Consistency and plausibility checks of the segmented PT skims (CalculateSegmentedSkimsMuc).
# Works for the test run and the full run. Results are printed and written to <skim_dir>/validation/.
#
# HARD checks (must be 0, otherwise the calculation is wrong):
#   - unreachable pairs: all matrices Infinity, frequency and availability 0
#   - reachable pairs: no NaN / Infinity / negative values
#   - shares and availability in [0, 1]; availability > 0 and frequency > 0
#   - parts sum to pt_ivt_total, pt_ivd_total, total_travel_time, total_distance
#   - bus skim: no feeder in-vehicle time, main share = 1
#   - walk distance / walk time = walk speed (3 km/h by default)
#   - total distance >= beeline distance
#   - frequency = 3600 / adaption time / 4
# NOTES (plausibility, to be looked at, not necessarily wrong):
#   - main-mode in-vehicle speed outside an expected range
#   - PT in-vehicle distance > 3 x beeline
#   - walking faster than PT
#   - a higher-level skim slower than a lower-level skim
# CAR (if car_travel_times.csv.gz / car_distances.csv.gz exist):
#   HARD: no NaN, non-negative, time and distance both finite or both Infinity, car distance >= beeline
#   NOTES: network speed outside a range, car distance > 2 x beeline, unreachable pairs, walking or PT faster than car

# ------------------------------------------------------------------ settings
r_script_dir <- "C:/models/matsim-libs/contribs/sbb-extensions/src/project/r_scripts"
skim_dir <- "C:/models/matsim-libs/contribs/sbb-extensions/src/project/output/skims_muc"
walk_speed <- 3.0 / 3.6            # m/s, non_network_walk default
main_speed_range_kmh <- list(bus = c(8, 60), btm = c(10, 80), all = c(15, 160))
car_speed_range_kmh <- c(10, 130)  # network speed (car distance / car time), interzonal
car_min_beeline_m <- 1000          # car detour / speed notes only for pairs at least this far apart
max_examples <- 20                 # example OD pairs written per check
print_od_table <- NA               # TRUE / FALSE; NA = only if <= 20 zones
# ------------------------------------------------------------------

source(file.path(r_script_dir, "00_skim_utils.R"))
options(width = 220)
out_dir <- dir_create(file.path(skim_dir, "validation"))
base <- load_base(skim_dir)
n_zones <- length(unique(base$from))
cat(sprintf("zones: %d, OD pairs: %d\n", n_zones, nrow(base)))

results <- list()
add_result <- function(type, skim, check, mask, dt, detail = NULL) {
  mask[is.na(mask)] <- FALSE
  n <- sum(mask)
  ex <- ""
  if (n > 0) {
    idx <- head(which(mask), max_examples)
    lab <- paste0(dt$from[idx], "->", dt$to[idx])
    if (!is.null(detail)) lab <- paste0(lab, " (", detail[idx], ")")
    ex <- paste(lab, collapse = "; ")
  }
  results[[length(results) + 1]] <<- data.table(type = type, skim = skim, check = check, n = n, examples = ex)
}

tol_time <- 2   # s
tol_dist <- 2   # m
totals <- list()

for (s in SKIMS) {
  cat("checking skim", s, "...\n")
  d <- load_skim(skim_dir, s, base)
  unreach <- is.infinite(d$total_travel_time)
  reach <- !unreach
  r <- function(x) round(x, 3)

  # --- unreachable pairs
  for (ind in setdiff(INDICATORS, c("main_mode_availability", "pt_frequency"))) {
    add_result("HARD", s, paste0("unreachable: ", ind, " is Infinity"), unreach & !is.infinite(d[[ind]]), d, r(d[[ind]]))
  }
  add_result("HARD", s, "unreachable: availability is 0", unreach & d$main_mode_availability != 0, d)
  add_result("HARD", s, "unreachable: frequency is 0", unreach & d$pt_frequency != 0, d)

  # --- reachable pairs: finite and non-negative
  for (ind in INDICATORS) {
    v <- d[[ind]]
    add_result("HARD", s, paste0("reachable: ", ind, " finite"), reach & !is.finite(v), d, v)
    add_result("HARD", s, paste0("reachable: ", ind, " >= 0"), reach & v < -1e-3, d, r(v))
  }

  # --- ranges
  for (ind in c("main_share_time", "main_share_dist", "main_mode_availability")) {
    v <- d[[ind]]
    add_result("HARD", s, paste0(ind, " in [0,1]"), reach & (v < -1e-4 | v > 1 + 1e-4), d, r(v))
  }
  add_result("HARD", s, "reachable: availability > 0", reach & d$main_mode_availability <= 0, d)
  add_result("HARD", s, "reachable: frequency > 0", reach & d$pt_frequency <= 0, d)

  # --- sums
  ivt <- d$pt_access_ivt + d$pt_main_ivt + d$pt_egress_ivt
  ivd <- d$pt_access_dist + d$pt_main_dist + d$pt_egress_dist
  tt <- d$walk_access_time + ivt + d$pt_transfer_walk_time + d$pt_transfer_wait_time + d$walk_egress_time
  td <- d$walk_access_dist + ivd + d$pt_transfer_walk_dist + d$walk_egress_dist
  add_result("HARD", s, "sum in-vehicle time = pt_ivt_total", reach & abs(ivt - d$pt_ivt_total) > tol_time, d, r(ivt - d$pt_ivt_total))
  add_result("HARD", s, "sum in-vehicle dist = pt_ivd_total", reach & abs(ivd - d$pt_ivd_total) > tol_dist, d, r(ivd - d$pt_ivd_total))
  add_result("HARD", s, "sum of times = total_travel_time", reach & abs(tt - d$total_travel_time) > tol_time, d, r(tt - d$total_travel_time))
  add_result("HARD", s, "sum of distances = total_distance", reach & abs(td - d$total_distance) > tol_dist, d, r(td - d$total_distance))

  # --- bus skim has no feeder
  if (s == "bus") {
    add_result("HARD", s, "bus: no feeder in-vehicle time", reach & (d$pt_access_ivt > 0.5 | d$pt_egress_ivt > 0.5), d)
    add_result("HARD", s, "bus: main share = 1", reach & abs(d$main_share_time - 1) > 1e-4, d, r(d$main_share_time))
  }

  # --- walk speed, distance, frequency
  ws <- ifelse(d$walk_access_time > 0, d$walk_access_dist / d$walk_access_time, walk_speed)
  add_result("HARD", s, "walk access speed = walk_speed", reach & abs(ws - walk_speed) > 0.01, d, r(ws))
  add_result("HARD", s, "total_distance >= beeline", reach & d$beeline > 0 & d$total_distance < 0.95 * d$beeline, d, r(d$total_distance / d$beeline))
  f2 <- 3600 / d$pt_adaptiontime / 4
  add_result("HARD", s, "frequency = 3600 / adaption / 4", reach & d$pt_adaptiontime > 0 & abs(f2 - d$pt_frequency) > 0.01, d, r(d$pt_frequency - f2))

  # --- plausibility notes
  rng <- main_speed_range_kmh[[s]]
  spd <- ifelse(d$pt_main_ivt > 60, d$pt_main_dist / d$pt_main_ivt * 3.6, NA)
  add_result("NOTE", s, sprintf("main in-vehicle speed outside %g-%g km/h", rng[1], rng[2]), reach & !is.na(spd) & (spd < rng[1] | spd > rng[2]), d, round(spd, 1))
  det <- ifelse(d$beeline > 0, d$pt_ivd_total / d$beeline, NA)
  add_result("NOTE", s, "PT in-vehicle distance > 3 x beeline (interzonal)", reach & !d$intrazonal & !is.na(det) & det > 3, d, round(det, 2))
  add_result("NOTE", s, "PT in-vehicle distance > 3 x beeline (intrazonal)", reach & d$intrazonal & !is.na(det) & det > 3, d, round(det, 2))
  add_result("NOTE", s, "walking faster than PT (interzonal)", reach & !d$intrazonal & d$walk_time < d$total_travel_time, d,
             sprintf("walk %.1f / pt %.1f min", d$walk_time / 60, d$total_travel_time / 60))
  add_result("NOTE", s, "walking faster than PT (intrazonal)", reach & d$intrazonal & d$walk_time < d$total_travel_time, d,
             sprintf("walk %.1f / pt %.1f min", d$walk_time / 60, d$total_travel_time / 60))
  add_result("NOTE", s, "unreachable (Infinity), interzonal", unreach & !d$intrazonal, d)
  add_result("NOTE", s, "unreachable (Infinity), intrazonal", unreach & d$intrazonal, d)
  add_result("NOTE", s, "availability < 0.2 (values rest on < 20 % of the hour)", reach & d$main_mode_availability < 0.2, d, r(d$main_mode_availability))

  totals[[s]] <- d$total_travel_time

  # --- small runs: print an OD table
  if (isTRUE(print_od_table) || (is.na(print_od_table) && n_zones <= 20)) {
    cat("\n---", s, "(minutes / km)\n")
    tab <- d[, .(OD = paste0(from, "->", to), beel_km = round(beeline / 1000, 1), tot_min = round(total_travel_time / 60, 1),
                 wAcc = round(walk_access_time / 60, 1), ptAcc = round(pt_access_ivt / 60, 1), main = round(pt_main_ivt / 60, 1),
                 ptEgr = round(pt_egress_ivt / 60, 1), wEgr = round(walk_egress_time / 60, 1),
                 trfWalk = round(pt_transfer_walk_time / 60, 1), trfWait = round(pt_transfer_wait_time / 60, 1),
                 trf = round(pt_transfer_count, 2), share = round(main_share_time, 2), avail = round(main_mode_availability, 2),
                 freq_h = round(pt_frequency, 1))]
    print(tab, nrows = 1000)
  }
  rm(d); gc(verbose = FALSE)
}

# --- car skim (loaded after the PT skims, so it is not copied into every PT table)
if (load_car(skim_dir, base)) {
  cat("checking car skim ...\n")
  d <- base
  r <- function(x) round(x, 3)
  creach <- is.finite(d$car_tt)

  # HARD
  add_result("HARD", "car", "car_tt not NaN", is.nan(d$car_tt), d)
  add_result("HARD", "car", "car_dist not NaN", is.nan(d$car_dist), d)
  add_result("HARD", "car", "car_tt >= 0", creach & d$car_tt < 0, d, r(d$car_tt))
  add_result("HARD", "car", "car_dist >= 0", is.finite(d$car_dist) & d$car_dist < 0, d, r(d$car_dist))
  add_result("HARD", "car", "car_tt and car_dist both finite or both Infinity", creach != is.finite(d$car_dist), d)
  add_result("HARD", "car", "car_dist >= beeline (interzonal)", creach & !d$intrazonal & d$car_dist < 0.95 * d$beeline, d,
             r(d$car_dist / d$beeline))

  # NOTES
  far <- creach & !d$intrazonal & d$beeline >= car_min_beeline_m
  spd <- ifelse(creach & d$car_tt > 0, d$car_dist / d$car_tt * 3.6, NA)
  add_result("NOTE", "car", sprintf("network speed outside %g-%g km/h", car_speed_range_kmh[1], car_speed_range_kmh[2]),
             far & !is.na(spd) & (spd < car_speed_range_kmh[1] | spd > car_speed_range_kmh[2]), d, round(spd, 1))
  det <- ifelse(d$beeline > 0, d$car_dist / d$beeline, NA)
  add_result("NOTE", "car", "car distance > 2 x beeline", far & !is.na(det) & det > 2, d, round(det, 2))
  add_result("NOTE", "car", "car unreachable (Infinity), interzonal", !creach & !d$intrazonal, d)
  add_result("NOTE", "car", "car unreachable (Infinity), intrazonal", !creach & d$intrazonal, d)
  add_result("NOTE", "car", "car time 0 (interzonal)", creach & !d$intrazonal & d$car_tt <= 0, d)
  add_result("NOTE", "car", "walking faster than car (interzonal)", creach & !d$intrazonal & d$walk_time < d$car_tt, d,
             sprintf("walk %.1f / car %.1f min", d$walk_time / 60, d$car_tt / 60))
  for (s in SKIMS) {
    pt <- totals[[s]]
    add_result("NOTE", "car", paste0("PT (", s, ") faster than car (interzonal)"),
               creach & is.finite(pt) & !d$intrazonal & pt < d$car_tt, d, sprintf("pt %.1f / car %.1f min", pt / 60, d$car_tt / 60))
  }

  if (isTRUE(print_od_table) || (is.na(print_od_table) && n_zones <= 20)) {
    cat("\n--- car (minutes / km)\n")
    print(d[, .(OD = paste0(from, "->", to), beel_km = round(beeline / 1000, 1), car_min = round(car_tt / 60, 1),
                car_km = round(car_dist / 1000, 1), speed_kmh = round(car_dist / car_tt * 3.6, 1),
                detour = round(car_dist / beeline, 2), bus_min = round(totals$bus / 60, 1), btm_min = round(totals$btm / 60, 1),
                all_min = round(totals$all / 60, 1), walk_min = round(walk_time / 60, 1))], nrows = 1000)
  }
}

# --- cross-skim comparison (informative: the higher-level skim only averages main-mode connections, so it can be slower)
cross <- function(hi, lo, label) {
  a <- totals[[hi]]; b <- totals[[lo]]
  m <- is.finite(a) & is.finite(b) & a > b * 1.1 & a > b + 60
  add_result("NOTE", "cross", label, m, base, sprintf("%.1f vs %.1f min", a / 60, b / 60))
}
cross("btm", "bus", "btm > 10 % slower than bus")
cross("all", "btm", "all > 10 % slower than btm")

res <- rbindlist(results)
fwrite(res, file.path(out_dir, "validation_results.csv"), sep = ";")

hard <- res[type == "HARD"]
cat(sprintf("\n==== HARD checks: %d checks, %d failing\n", nrow(hard), sum(hard$n > 0)))
if (any(hard$n > 0)) print(hard[n > 0, .(skim, check, n, examples = substr(examples, 1, 120))], nrows = 200)
cat("\n==== NOTES\n")
print(res[type == "NOTE" & n > 0, .(skim, check, n, examples = substr(examples, 1, 100))], nrows = 200)
cat("\nfull results:", file.path(out_dir, "validation_results.csv"), "\n")
