// Copyright 2023 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// The cloudScheduler tool allows creating, updating and deleting cloud
// scheduler jobs from xml config file

package main

import (
	"encoding/json"
	"encoding/xml"
	"fmt"
	"io"
	"log"
	"os"
	"os/exec"
	"strings"
)

func main() {
	if len(os.Args) < 3 || os.Args[1] == "" || os.Args[2] == "" {
		panic("Error - invalid parameters:\nFirst parameter required - config file path;\nSecond parameter required - project name")
	}

	// Config file path
	configFileLocation := os.Args[1]
	// Project name where to submit the tasks
	projectName := os.Args[2]

	log.Default().Println("Filepath " + configFileLocation)

	xmlFile, err := os.Open(configFileLocation)
	if err != nil {
		panic(err)
	}
	defer xmlFile.Close()

	type Cron struct {
		XMLName     xml.Name `xml:"cron"`
		URL         string   `xml:"url"`
		Description string   `xml:"description"`
		Schedule    string   `xml:"schedule"`
		Target      string   `xml:"target"`
		Name        string   `xml:"name"`
		Method      string   `xml:"method"`
	}

	type Cronentries struct {
		XMLName xml.Name `xml:"cronentries"`
		Cron    []Cron   `xml:"cron"`
	}

	type ServiceAccount struct {
		DisplayName string `json:"displayName"`
		Email       string `json:"email"`
	}

	type ExistingJob struct {
		Name  string `json:"name"`
		State string `json:"state"`
	}

	byteValue, _ := io.ReadAll(xmlFile)

	var cronEntries Cronentries

	if err := xml.Unmarshal(byteValue, &cronEntries); err != nil {
		panic(err)
	}

	getArgs := func(cronRecord Cron, operationType string, serviceAccountEmail string) []string {
		// Cloud Schedule doesn't allow description of more than 499 chars and \n
		var description string
		if len(cronRecord.Description) > 499 {
			description = cronRecord.Description[:499]
		} else {
			description = cronRecord.Description
		}
		description = strings.ReplaceAll(description, "\n", " ")

		return []string{
			"--project", projectName,
			"scheduler", "jobs", operationType,
			"http", cronRecord.Name,
			"--location", "us-central1",
			"--schedule", cronRecord.Schedule,
			"--uri", fmt.Sprintf("https://%s-dot-%s.appspot.com%s", cronRecord.Target, projectName, cronRecord.URL),
			"--description", description,
			"--http-method", strings.ToLower(cronRecord.Method),
			"--oidc-service-account-email", serviceAccountEmail,
			"--oidc-token-audience", projectName,
		}
	}

	// Get existing jobs from Cloud Scheduler
	var allExistingJobs []ExistingJob
	cmdGetExistingList := exec.Command("gcloud", "scheduler", "jobs", "list", "--project="+projectName, "--location=us-central1", "--format=json")
	cmdGetExistingListOutput, cmdGetExistingListError := cmdGetExistingList.CombinedOutput()
	if cmdGetExistingListError != nil {
		panic("Can't obtain existing cloud scheduler jobs for " + projectName)
	}
	err = json.Unmarshal(cmdGetExistingListOutput, &allExistingJobs)
	if err != nil {
		panic("Failed to parse existing jobs from cloud schedule")
	}

	// Sync deleted jobs
	enabledOnlyExistingJobs := map[string]bool{}
	for i := 0; i < len(allExistingJobs); i++ {
		jobName := strings.Split(allExistingJobs[i].Name, "jobs/")[1]
		toBeDeleted := true
		if allExistingJobs[i].State == "ENABLED" {
			enabledOnlyExistingJobs[jobName] = true
		}
		for i := 0; i < len(cronEntries.Cron); i++ {
			if cronEntries.Cron[i].Name == jobName {
				toBeDeleted = false
				break
			}
		}
		if toBeDeleted {
			cmdDelete := exec.Command("gcloud", "scheduler", "jobs", "delete", jobName, "--project="+projectName, "--quiet")
			cmdDeleteOutput, cmdDeleteError := cmdDelete.CombinedOutput()
			log.Default().Println("Deleting cloud scheduler job " + jobName)
			if cmdDeleteError != nil {
				panic(string(cmdDeleteOutput))
			}
		}
	}

	// Find service account email
	var serviceAccounts []ServiceAccount
	var serviceAccountEmail string
	cmdGetServiceAccounts := exec.Command("gcloud", "iam", "service-accounts", "list", "--project="+projectName, "--format=json")
	cmdGetServiceAccountsOutput, cmdGetServiceAccountsError := cmdGetServiceAccounts.CombinedOutput()
	if cmdGetServiceAccountsError != nil {
		panic(cmdGetServiceAccountsError)
	}
	err = json.Unmarshal(cmdGetServiceAccountsOutput, &serviceAccounts)
	if err != nil {
		panic(err)
	}
	for i := 0; i < len(serviceAccounts); i++ {
		if serviceAccounts[i].DisplayName == "cloud-scheduler" {
			serviceAccountEmail = serviceAccounts[i].Email
			break
		}
	}
	if serviceAccountEmail == "" {
		panic("Service account for cloud scheduler is not created for " + projectName)
	}

	// Sync created and updated jobs
	for i := 0; i < len(cronEntries.Cron); i++ {
		cmdType := "update"
		if enabledOnlyExistingJobs[cronEntries.Cron[i].Name] != true {
			cmdType = "create"
		}

		syncCommand := exec.Command("gcloud", getArgs(cronEntries.Cron[i], cmdType, serviceAccountEmail)...)
		syncCommandOutput, syncCommandError := syncCommand.CombinedOutput()
		log.Default().Println(cmdType + " cloud scheduler job " + cronEntries.Cron[i].Name)
		if syncCommandError != nil {
			panic(string(syncCommandOutput))
		}
	}
}
