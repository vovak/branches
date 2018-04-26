# branches

This repository is the online appendix of our ASE 2018 submission.

The `results` folder contains full results per project for RQ1 and for the reviewer recommendation part of RQ2, as well as lists of projects used for the evaluation of defect prediction and change recommendation algorithms. The rest of the results are provided in the paper.

The code that we have used to download and process the data (code reviews, repositories, and change recommendation) is located in the `processor` folder. 

The `processor` depends on `git2neo` -- a tool to load Git metadata to neo4j databases and retrieve the histories, which is described in Section III.B of the paper. 
We plan to prepare a proper release of `git2neo` upon publication of the paper; for now the source code is provided as is to facilitate the evaluation of our work by ASE reviewers.
