# 04_prepare_report_data.R
#
# Reads the full skim run once and writes small extracts for the HTML report (05_skim_report.qmd):
#
#   report_data/od_selected.csv     all OD pairs FROM the selected zones (5 x 5452), per mode, with all time components
#   report_data/per_origin.csv      one row per origin zone and mode: share reachable, mean availability, median main share
#   report_data/od_sample.csv       random sample of interzonal OD pairs: beeline, band, travel time per mode
#   report_data/band_quantiles.csv  full-data quantiles by distance band and mode: door-to-door speed, PT / car ratio
#   report_data/zones.gpkg          zone polygons (simplified) for the static maps
#   report_data/sampling_points.csv sampling points of all zones (for the zone explorer)
#   report_data/meta.csv            run information for the "About" page
#
# Runtime: about 10-20 min for the full run (each of the ~75 matrices is read once). Memory: ~6-8 GB.

suppressPackageStartupMessages({
  library(data.table)
  library(sf)
})

# ------------------------------------------------------------------ settings
r_script_dir <- "C:/models/matsim-libs/contribs/sbb-extensions/src/project/r_scripts"
skim_dir <- "C:/models/matsim-libs/contribs/sbb-extensions/src/project/output/skims_muc"
sampling_points_file <- "C:/models/matsim-libs/contribs/sbb-extensions/src/project/input/zone_coordinates_muc_5452.csv"
zones_shapefile <- "Z:/projects/2022/fabilut/zones/zones_Munich-Bavaria_size_5452_popCap_5000_crs_31468.shp"
zones_id_attribute <- "TAZ_ID"
selected_zones <- c("2641", "3441", "517", "4405", "3084")
sample_size <- 200000              # interzonal OD pairs for scatter plots
simplify_tolerance_m <- 50         # polygon simplification for the maps
seed <- 4711
# ------------------------------------------------------------------

source(file.path(r_script_dir, "00_skim_utils.R"))
out_dir <- dir_create(file.path(skim_dir, "report_data"))
set.seed(seed)
t_start <- Sys.time()
log_step <- function(...) cat(format(Sys.time(), "%H:%M:%S"), ..., "\n")

# ------------------------------------------------------------------ base, car
log_step("loading beeline / walk / car")
base <- add_distance_band(load_base(skim_dir))
has_car <- load_car(skim_dir, base)
n_od <- nrow(base)
zones_all <- unique(base$from)
missing_sel <- setdiff(selected_zones, zones_all)
if (length(missing_sel) > 0) stop("selected zones not in the skims: ", paste(missing_sel, collapse = ", "))

sel_idx <- which(base$from %in% selected_zones)
inter_idx <- which(!base$intrazonal)
sample_idx <- sort(inter_idx[sample.int(length(inter_idx), min(length(inter_idx), sample_size))])

MODES <- c("car", SKIMS)
MODE_LABELS <- c(car = "car", SKIM_LABELS)

# components kept for the selected zones
SEL_COLS <- c("total_travel_time", "total_distance", "walk_access_time", "pt_access_ivt", "pt_main_ivt", "pt_egress_ivt",
              "walk_egress_time", "pt_transfer_walk_time", "pt_transfer_wait_time", "pt_transfer_count",
              "main_mode_availability", "main_share_time", "pt_frequency", "pt_ivd_total", "pt_main_dist")

q5 <- function(x) {
  x <- x[is.finite(x)]
  if (length(x) == 0) return(list(n = 0L, p05 = NA_real_, p25 = NA_real_, p50 = NA_real_, p75 = NA_real_, p95 = NA_real_))
  qs <- quantile(x, c(0.05, 0.25, 0.5, 0.75, 0.95), names = FALSE)
  list(n = length(x), p05 = qs[1], p25 = qs[2], p50 = qs[3], p75 = qs[4], p95 = qs[5])
}

sel_list <- list()
origin_list <- list()
quant_list <- list()
sample_dt <- base[sample_idx, .(from, to, beeline, dist_band, walk_time)]
car_tt_all <- if (has_car) base$car_tt else NULL

# ------------------------------------------------------------------ car
if (has_car) {
  log_step("car")
  creach <- is.finite(base$car_tt)
  sel_list$car <- base[sel_idx, .(from, to, mode = "car", beeline, dist_band, intrazonal,
                                  total_travel_time = car_tt, total_distance = car_dist,
                                  main_mode_availability = as.numeric(is.finite(car_tt)))]
  origin_list$car <- base[intrazonal == FALSE, .(mode = "car", share_reachable = mean(is.finite(car_tt)),
                                                  mean_availability = mean(is.finite(car_tt)),
                                                  median_main_share = NA_real_,
                                                  median_tt_min = median(car_tt[is.finite(car_tt)]) / 60), by = from]
  spd <- ifelse(creach & base$car_tt > 0 & !base$intrazonal, base$beeline / base$car_tt * 3.6, NA_real_)
  quant_list$car_speed <- data.table(dist_band = base$dist_band, v = spd)[!is.na(v), q5(v), by = dist_band][, `:=`(mode = "car", measure = "d2d_speed_kmh")]
  sample_dt[, car_tt := base$car_tt[sample_idx]]
  rm(spd, creach)
}

# ------------------------------------------------------------------ PT skims
for (s in SKIMS) {
  log_step("skim", s)
  # full columns only for the aggregates; all other indicators only for the rows of the selected zones
  full_cols <- c("total_travel_time", "main_mode_availability", "main_share_time")
  d <- base[, .(from, to, beeline, intrazonal, dist_band)]
  sel <- base[sel_idx, .(from, to, beeline, dist_band, intrazonal)]
  for (ind in SEL_COLS) {
    f <- file.path(skim_dir, paste0(s, "_", ind, ".csv.gz"))
    v <- read_matrix(f)
    if (length(v) != n_od) stop(basename(f), " has ", length(v), " rows, expected ", n_od)
    set(sel, j = ind, value = v[sel_idx])
    if (ind %in% full_cols) set(d, j = ind, value = v)
    rm(v)
  }
  reach <- is.finite(d$total_travel_time)
  sel_list[[s]] <- sel[, mode := s]

  origin_list[[s]] <- d[intrazonal == FALSE, .(mode = s, share_reachable = mean(is.finite(total_travel_time)),
                                               mean_availability = mean(main_mode_availability),
                                               median_main_share = median(main_share_time[is.finite(main_share_time)]),
                                               median_tt_min = median(total_travel_time[is.finite(total_travel_time)]) / 60), by = from]

  spd <- ifelse(reach & !d$intrazonal & d$total_travel_time > 0, d$beeline / d$total_travel_time * 3.6, NA_real_)
  quant_list[[paste0(s, "_speed")]] <- data.table(dist_band = d$dist_band, v = spd)[!is.na(v), q5(v), by = dist_band][, `:=`(mode = s, measure = "d2d_speed_kmh")]
  if (has_car) {
    ratio <- ifelse(reach & is.finite(car_tt_all) & car_tt_all > 0 & !d$intrazonal, d$total_travel_time / car_tt_all, NA_real_)
    quant_list[[paste0(s, "_ratio")]] <- data.table(dist_band = d$dist_band, v = ratio)[!is.na(v), q5(v), by = dist_band][, `:=`(mode = s, measure = "pt_car_ratio")]
    rm(ratio)
  }
  set(sample_dt, j = paste0(s, "_tt"), value = d$total_travel_time[sample_idx])
  rm(d, spd, reach); gc(verbose = FALSE)
}

# ------------------------------------------------------------------ write tables
log_step("writing tables")
od_sel <- rbindlist(sel_list, fill = TRUE)
num_cols <- names(od_sel)[vapply(od_sel, is.numeric, logical(1))]
for (cc in num_cols) set(od_sel, i = which(is.infinite(od_sel[[cc]])), j = cc, value = NA_real_)  # Infinity -> empty
od_sel[, reachable := !is.na(total_travel_time)]
fwrite(od_sel, file.path(out_dir, "od_selected.csv"), sep = ";")

fwrite(rbindlist(origin_list, fill = TRUE), file.path(out_dir, "per_origin.csv"), sep = ";")

for (cc in names(sample_dt)[grepl("_tt$", names(sample_dt))]) set(sample_dt, i = which(is.infinite(sample_dt[[cc]])), j = cc, value = NA_real_)
fwrite(sample_dt, file.path(out_dir, "od_sample.csv"), sep = ";")

fwrite(rbindlist(quant_list, fill = TRUE), file.path(out_dir, "band_quantiles.csv"), sep = ";")

# ------------------------------------------------------------------ zones and sampling points
log_step("zones")
zones <- st_read(zones_shapefile, quiet = TRUE)
zones$zone_id <- as.character(zones[[zones_id_attribute]])
zones <- zones[zones$zone_id %in% zones_all, "zone_id"]
zones <- st_simplify(zones, preserveTopology = TRUE, dTolerance = simplify_tolerance_m)
st_write(zones, file.path(out_dir, "zones.gpkg"), delete_dsn = TRUE, quiet = TRUE)

if (!is.null(sampling_points_file) && file.exists(sampling_points_file)) {
  file.copy(sampling_points_file, file.path(out_dir, "sampling_points.csv"), overwrite = TRUE)
}

# ------------------------------------------------------------------ meta
first_file <- file.path(skim_dir, "bus_total_travel_time.csv.gz")
meta <- data.table(
  item = c("skim_dir", "n_zones", "n_od_pairs", "selected_zones", "sample_size", "skims_written", "car_skim", "prepared"),
  value = c(skim_dir, length(zones_all), n_od, paste(selected_zones, collapse = ", "), length(sample_idx),
            format(file.info(first_file)$mtime, "%Y-%m-%d"), ifelse(has_car, "yes", "no"), format(Sys.time(), "%Y-%m-%d %H:%M"))
)
fwrite(meta, file.path(out_dir, "meta.csv"), sep = ";")

log_step("done in", round(as.numeric(difftime(Sys.time(), t_start, units = "mins")), 1), "min ->", out_dir)
