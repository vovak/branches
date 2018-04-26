library(readr)
library(ggplot2)
library(gtable)
library(gridExtra)

ecosystems <- c("apache", "github", "openstack", "eclipse")

is.nan.data.frame <- function(x)
  do.call(cbind, lapply(x, is.nan))

base_breaks <- function(n = 10){
  function(x) {
    axisTicks(log10(range(x, na.rm = TRUE)), log = TRUE, n = n)
  }
}

datas <- list()
index <- 1
for(system in ecosystems) {
  dat <- read_csv(paste("~/work/ASE2018/analysis/results/results-rq1-", system, ".txt", sep=""))
  dat[is.nan(dat)] <- 0
  dat$ecosystem <- system
  dat <- dat[dat$filesInTree > 0,]
  datas[[index]] <- dat
  index <- index+1
}

dat <- do.call("rbind", datas)
#precalculate some variables...
dat$mergeCommitFraction <- dat$mergeCommits/dat$reachableCommits
dat$reachableCommitFraction <- dat$reachableCommits/dat$totalCommits
dat$mainBranchReachableFraction <- dat$mainBranchReachableCommits/dat$reachableCommits

dat$fractionOfFilesWithDiff = dat$filesWithDifferentHistories / dat$filesInTree
dat$historyLengthRatioTotal = dat$avgLinearHistoryLength / dat$avgFullHistoryLength
dat$historyLengthRatioForDiff = dat$avgLinearHistoryLengthForFilesWithDifferentHistories / dat$avgFullHistoryLengthForFilesWithDifferentHistories
dat$avgContributorsRatioTotal = dat$avgAuthorsPerFileLinearHistory / dat$avgAuthorsPerFileFullHistory
dat$avgContributorsRatioDiff = dat$avgAuthorsPerFileLinearHistoryForFilesWithDifference / dat$avgAuthorsPerFileFullHistoryForFilesWithDifference

theme_set(theme_minimal())


compare_distributions <- function(varName, title) {
  sample <- dat[,c('ecosystem', varName)]
  colnames(sample) <- c('ecosystem', 'var')
  ggplot(sample, aes(x=ecosystem, y=var)) + geom_boxplot() +
    ylab(varName) +
    xlab("") +
    theme(axis.text.x = element_text(size=12), text = element_text(size=12), plot.title = element_text(size=12, hjust = 0.5)) +
    ggtitle(title)
}

compare_distributions_log <- function(varName, title) {
  compare_distributions(varName, title) + scale_y_continuous(trans='log10', breaks = c(1,10, 100, 1000, 10000, 100000), labels = c("1", "10", "100", "1,000", "10,000", "100,000"))
}

generate_charts <- function() {
  p1 <- compare_distributions_log("totalCommits", "Count of commits in the repository")
  p2 <- compare_distributions_log("filesInTree", "Files in tree at HEAD")
  p3 <- compare_distributions_log("uniqueAuthorsInRepo", "Unique authors of commits")
  p4 <- compare_distributions("mergeCommitFraction", "Fraction of merge commits\n(among commits reachable from HEAD)")
  p5 <- compare_distributions("reachableCommitFraction", "Fraction of commits reachable from HEAD\n(among all commits in the repository)")
  p6 <- compare_distributions("mainBranchReachableFraction", "Fraction of commits reachable\nfrom HEAD via the first parent\n(among commits reachable from HEAD)")
  p7 <- compare_distributions("fractionOfFilesWithDiff", "Fraction of files with different\nsizes of history (first-parent/full)")
  p8 <- compare_distributions("historyLengthRatioTotal", "Average ratio of history lengths\n(first-parent/full, all files)")
  p9 <- compare_distributions("avgContributorsRatioTotal", "Average ratio of numbers of contributors\nin first-parent/full histories (all files)")

  p <- grid.arrange(p1, p2, p3, p4, p5, p6, p7, p8, p9, nrow=3)

  g <- arrangeGrob(p1, p2, p3, p4, p5, p6, p7, p8, p9, nrow=3)

  ggsave(file="/Users/*blinded*/work/fse18_git2neo/paper/img/compare_ecosystems.pdf", g)
}


generate_projects_table <- function() {
  rows <- list()
  i <- 1
  for(sys in ecosystems) {
    dat_ecosystem <- dat[dat$ecosystem == sys,]
    dat_diff <- dat_ecosystem[dat_ecosystem$filesWithDifferentHistories > 0,]

    r <- list()

    r$ecosystem <- sys
    r$nprojects <- nrow(dat_ecosystem)
    r$projectsWithDiff <- nrow(dat_diff)
    r$totalCommits <- sum(dat_ecosystem$totalCommits)
    r$commitsInProjectsWithDiff <- sum(dat_diff$totalCommits)
    r$filesInProjectsWithDiff <- sum(dat_diff$filesInTree)
    r$filesWithDifferentHistories <- sum(dat_ecosystem$filesWithDifferentHistories)
    r$files <- sum(dat_ecosystem$filesInTree)


    print(r$ecosystem)
    print("Median history length ratio (all)")
    print(median(dat_ecosystem$historyLengthRatioTotal))
    print("Median history length ratio (diff)")
    print(median(dat_ecosystem$historyLengthRatioForDiff, na.rm = TRUE))

    print("Median contributors size ratio (all files)")
    print(median(dat_ecosystem$avgContributorsRatioTotal, na.rm = TRUE))

    print("Median # commits:")
    print(median(dat_ecosystem$totalCommits))

    print("Median # authors:")
    print(median(dat_ecosystem$uniqueAuthorsInRepo))

    print("Median % diff histories")
    print(median(dat_ecosystem$filesWithDifferentHistories / dat_ecosystem$filesInTree))



    print(r$nprojects)
    print(r$projectsWithDiff)
    print(r$totalCommits)
    print(r$commitsInProjectsWithDiff)
    print(r$filesInProjectsWithDiff)
    print(r$filesWithDifferentHistories)

    print(r)

    rows[[i]] <- r
    i <- i+1
  }

  tbl<-do.call(rbind,lapply(rows,data.frame))
  tbl
}

save_df <- function(d, filename) {
  write.csv(d, file=filename)
}

generate_charts()
table <- generate_projects_table()
save_df(table, "ecosystems.csv")
