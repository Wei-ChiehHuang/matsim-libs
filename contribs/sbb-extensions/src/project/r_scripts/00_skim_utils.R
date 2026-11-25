# Shared helpers for reading the segmented PT skims written by CalculateSegmentedSkimsMuc.
# Sourced by 01_validate_skims.R, 02_journey_planner_comparison.R and 03_full_run_statistics.R.
#
# Matrix files: <prefix><indicator>.csv.gz with columns FROM;TO;VALUE, sorted by FROM, TO.
# All matrices of one run have the same zones in the same order, so values are bound by position
# (checked against the beeline matrix), which keeps memory low for the full run (5452^2 = 29.7 M rows).

suppressPackageStartupMessages({
  library(data.table)
})

SKIMS <- c("bus", "btm", "all")
SKIM_LABELS <- c(bus = "bus", btm = "bus + tram/metro", all = "bus + tram/metro + rail")

INDICATORS <- c(
  "walk_access_time", "walk_access_dist", "walk_egress_time", "walk_egress_dist",
  "pt_access_ivt", "pt_access_dist", "pt_main_ivt", "pt_main_dist", "pt_egress_ivt", "pt_egress_dist",
  "pt_transfer_walk_time", "pt_transfer_walk_dist", "pt_transfer_wait_time", "pt_transfer_count",
  "pt_ivt_total", "pt_ivd_total", "total_travel_time", "total_distance",
  "main_share_time", "main_share_dist", "main_mode_availability", "pt_adaptiontime", "pt_frequency"
)

# Reads one matrix file. ids = TRUE also returns FROM/TO (as character).
read_matrix <- function(file, ids = FALSE) {
  if (!file.exists(file)) stop("missing file: ", file)
  # fread parses "Infinity" / "NaN" directly as numeric (much faster than reading text and converting)
  dt <- fread(file, sep = ";", header = TRUE, select = if (ids) 1:3 else 3,
              colClasses = if (ids) list(character = 1:2, numeric = 3) else list(numeric = 3), showProgress = FALSE)
  val <- dt[[ncol(dt)]]
  if (!is.numeric(val)) val <- suppressWarnings(as.numeric(val))
  if (ids) data.table(from = dt[[1]], to = dt[[2]], value = val) else val
}

# Reference table: from, to, beeline (m), walk_time (s), walk_dist (m), intrazonal flag.
load_base <- function(dir) {
  base <- read_matrix(file.path(dir, "beeline_distances.csv.gz"), ids = TRUE)
  setnames(base, "value", "beeline")
  base[, walk_time := read_matrix(file.path(dir, "walk_time.csv.gz"))]
  base[, walk_dist := read_matrix(file.path(dir, "walk_dist.csv.gz"))]
  base[, intrazonal := from == to]
  base[]
}

# Adds the given indicators of one skim as columns to a copy of base. Checks that the zone order is identical.
load_skim <- function(dir, prefix, base, indicators = INDICATORS, check_order = TRUE) {
  dt <- copy(base)
  first <- TRUE
  for (ind in indicators) {
    f <- file.path(dir, paste0(prefix, "_", ind, ".csv.gz"))
    if (first && check_order) {
      m <- read_matrix(f, ids = TRUE)
      if (nrow(m) != nrow(dt) || !identical(m$from, dt$from) || !identical(m$to, dt$to)) {
        stop("zone order of ", basename(f), " differs from beeline_distances.csv.gz")
      }
      set(dt, j = ind, value = m$value)
      first <- FALSE
    } else {
      v <- read_matrix(f)
      if (length(v) != nrow(dt)) stop(basename(f), " has ", length(v), " rows, expected ", nrow(dt))
      set(dt, j = ind, value = v)
    }
  }
  dt[]
}

# Car skim (CalculateSkimMatrices network matrices): adds car_tt (s) and car_dist (m) to dt by reference.
# Returns FALSE (and adds nothing) if the car files do not exist.
CAR_FILES <- c(car_tt = "car_travel_times.csv.gz", car_dist = "car_distances.csv.gz")

load_car <- function(dir, dt) {
  files <- file.path(dir, CAR_FILES)
  if (!all(file.exists(files))) {
    message("no car skim found in ", dir, " (", paste(CAR_FILES, collapse = ", "), "), car checks skipped")
    return(FALSE)
  }
  m <- read_matrix(files[1], ids = TRUE)
  if (nrow(m) != nrow(dt) || !identical(m$from, dt$from) || !identical(m$to, dt$to)) {
    stop("zone order of ", CAR_FILES[1], " differs from beeline_distances.csv.gz")
  }
  set(dt, j = "car_tt", value = m$value)
  v <- read_matrix(files[2])
  if (length(v) != nrow(dt)) stop(CAR_FILES[2], " has ", length(v), " rows, expected ", nrow(dt))
  set(dt, j = "car_dist", value = v)
  TRUE
}

# Distance bands used in summaries (beeline, km)
DIST_BANDS <- c("intrazonal", "0-2 km", "2-5 km", "5-10 km", "10-25 km", "25-50 km", "50-100 km", ">100 km")

add_distance_band <- function(dt) {
  band <- as.character(cut(dt$beeline / 1000, breaks = c(-Inf, 2, 5, 10, 25, 50, 100, Inf),
                           labels = DIST_BANDS[-1], right = FALSE))
  band[dt$intrazonal] <- "intrazonal"
  dt[, dist_band := factor(band, levels = DIST_BANDS)]
  dt[]
}

dir_create <- function(path) {
  if (!dir.exists(path)) dir.create(path, recursive = TRUE)
  path
}
