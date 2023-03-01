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

	type Task struct {
		XMLName     xml.Name `xml:"task"`
		URL         string   `xml:"url"`
		Description string   `xml:"description"`
		Schedule    string   `xml:"schedule"`
		Name        string   `xml:"name"`
	}

	type Taskentries struct {
		XMLName xml.Name `xml:"taskentries"`
		Task    []Task   `xml:"task"`
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

	var taskEntries Taskentries

	if err := xml.Unmarshal(byteValue, &taskEntries); err != nil {
		panic("Failed to unmarshal taskentries: " + err.Error())
	}

	getArgs := func(taskRecord Task, operationType string, serviceAccountEmail string) []string {
		// Cloud Schedule doesn't allow description of more than 499 chars and \n
		var description string
		if len(taskRecord.Description) > 499 {
			description = taskRecord.Description[:499]
		} else {
			description = taskRecord.Description
		}
		description = strings.ReplaceAll(description, "\n", " ")

		return []string{
			"--project", projectName,
			"scheduler", "jobs", operationType,
			"http", taskRecord.Name,
			"--location", "us-central1",
			"--schedule", taskRecord.Schedule,
			"--uri", fmt.Sprintf("https://backend-dot-%s.appspot.com%s", projectName, taskRecord.URL),
			"--description", description,
			"--http-method", "get",
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
		panic("Failed to parse existing jobs from cloud schedule: " + err.Error())
	}

	// Sync deleted jobs
	enabledExistingJobs := map[string]bool{}
	for i := 0; i < len(allExistingJobs); i++ {
		jobName := strings.Split(allExistingJobs[i].Name, "jobs/")[1]
		toBeDeleted := true
		if allExistingJobs[i].State == "ENABLED" {
			enabledExistingJobs[jobName] = true
		}
		for i := 0; i < len(taskEntries.Task); i++ {
			if taskEntries.Task[i].Name == jobName {
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
		panic(err.Error())
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
	for i := 0; i < len(taskEntries.Task); i++ {
		cmdType := "update"
		if enabledExistingJobs[taskEntries.Task[i].Name] != true {
			cmdType = "create"
		}

		syncCommand := exec.Command("gcloud", getArgs(taskEntries.Task[i], cmdType, serviceAccountEmail)...)
		syncCommandOutput, syncCommandError := syncCommand.CombinedOutput()
		log.Default().Println(cmdType + " cloud scheduler job " + taskEntries.Task[i].Name)
		if syncCommandError != nil {
			panic(string(syncCommandOutput))
		}
	}
}
