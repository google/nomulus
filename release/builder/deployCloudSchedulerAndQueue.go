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

// The deployerForSchedulerAndTasks tool allows creating, updating and deleting
// cloud scheduler jobs and tasks from xml config file

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

var projectName string

type SyncManager[T Task | Queue] interface {
	syncCreated(e T) ([]byte, error)
	syncDeleted(e string) ([]byte, error)
	syncUpdated(e T) ([]byte, error)
	fetchExistingRecords() ExistingEntries
	getXmlEntries() []T
	getEntryName(e T) string
}

type ServiceAccount struct {
	DisplayName string `json:"displayName"`
	Email       string `json:"email"`
}

type Queue struct {
	Name                    string `xml:"name"`
	MaxAttempts             string `xml:"max-attempts"`
	MaxBackoff              string `xml:"max-backoff"`
	MaxConcurrentDispatches string `xml:"max-concurrent-dispatches"`
	MaxDispatchesPerSecond  string `xml:"max-dispatches-per-second"`
	MaxDoublings            string `xml:"max-doublings"`
	MaxRetryDuration        string `xml:"max-retry-duration"`
	MinBackoff              string `xml:"min-backoff"`
	RoutingOverride         string `xml:"routing-override"`
	MaxBurstSize            string `xml:"max-burst-size"`
}

type Task struct {
	URL         string `xml:"url"`
	Description string `xml:"description"`
	Schedule    string `xml:"schedule"`
	Name        string `xml:"name"`
}

type QueuesSyncManager struct {
	Queues []Queue
}

type TasksSyncManager struct {
	Tasks               []Task
	ServiceAccountEmail string
}

type XmlEntries struct {
	XMLName xml.Name `xml:"entries"`
	Tasks   []Task   `xml:"task"`
	Queues  []Queue  `xml:"queue"`
}

type ExistingEntry struct {
	Name string `json:"name"`
}

type ExistingEntries struct {
	asAMap map[string]bool
}

func handleSyncErrors(response []byte, err error) {
	if err != nil {
		panic(string(response) + " " + err.Error())
	}
}

func sync[T Queue | Task](manager SyncManager[T]) {
	// State from xml file
	xmlEntries := manager.getXmlEntries()
	// Fetch tasks currently in GCP
	existingTasks := manager.fetchExistingRecords()
	// Find and sync deleted tasks
	for taskName := range existingTasks.asAMap {
		toBeDeleted := true
		for j := 0; j < len(xmlEntries); j++ {
			if manager.getEntryName(xmlEntries[j]) == taskName {
				toBeDeleted = false
				break
			}
		}
		if toBeDeleted {
			handleSyncErrors(manager.syncDeleted(taskName))
		}
	}
	// Sync created and updated tasks
	for j := 0; j < len(xmlEntries); j++ {
		if existingTasks.asAMap[manager.getEntryName(xmlEntries[j])] {
			handleSyncErrors(manager.syncUpdated(xmlEntries[j]))
		} else {
			handleSyncErrors(manager.syncCreated(xmlEntries[j]))
		}
	}
}

var cloudSchedulerServiceAccountEmail string

func getCloudSchedulerServiceAccountEmail() string {
	if cloudSchedulerServiceAccountEmail != "" {
		return cloudSchedulerServiceAccountEmail
	}

	var serviceAccounts []ServiceAccount
	cmdGetServiceAccounts := exec.Command("gcloud", "iam", "service-accounts", "list", "--project="+projectName, "--format=json")
	cmdGetServiceAccountsOutput, cmdGetServiceAccountsError := cmdGetServiceAccounts.CombinedOutput()
	if cmdGetServiceAccountsError != nil {
		panic(cmdGetServiceAccountsError)
	}
	err := json.Unmarshal(cmdGetServiceAccountsOutput, &serviceAccounts)
	if err != nil {
		panic("Failed to parse service account response: " + err.Error())
	}
	for i := 0; i < len(serviceAccounts); i++ {
		if serviceAccounts[i].DisplayName == "cloud-scheduler" {
			cloudSchedulerServiceAccountEmail = serviceAccounts[i].Email
			return serviceAccounts[i].Email
		}
	}
	panic("Service account for cloud scheduler is not created for " + projectName)
}

func (manager TasksSyncManager) getEntryName(task Task) string {
	return task.Name
}

func (manager TasksSyncManager) getXmlEntries() []Task {
	return manager.Tasks
}

func (manager TasksSyncManager) getArgs(task Task, operationType string) []string {
	// Cloud Schedule doesn't allow description of more than 499 chars and \n
	var description string
	if len(task.Description) > 499 {
		description = task.Description[:499]
	} else {
		description = task.Description
	}
	description = strings.ReplaceAll(description, "\n", " ")

	return []string{
		"--project", projectName,
		"scheduler", "jobs", operationType,
		"http", task.Name,
		"--location", "us-central1",
		"--schedule", task.Schedule,
		"--uri", fmt.Sprintf("https://backend-dot-%s.appspot.com%s", projectName, strings.TrimSpace(task.URL)),
		"--description", description,
		"--http-method", "get",
		"--oidc-service-account-email", getCloudSchedulerServiceAccountEmail(),
		"--oidc-token-audience", projectName,
	}
}

func (manager TasksSyncManager) fetchExistingRecords() ExistingEntries {
	return getExistingEntries(exec.Command("gcloud", "scheduler", "jobs", "list", "--project="+projectName, "--location=us-central1", "--format=json"))
}

func (manager TasksSyncManager) syncDeleted(taskName string) ([]byte, error) {
	log.Default().Println("Deleting cloud scheduler task " + taskName)
	cmdDelete := exec.Command("gcloud", "scheduler", "jobs", "delete", taskName, "--project="+projectName, "--quiet")
	return cmdDelete.CombinedOutput()
}

func (manager TasksSyncManager) syncCreated(task Task) ([]byte, error) {
	log.Default().Println("Creating cloud scheduler task " + task.Name)
	syncCommand := exec.Command("gcloud", manager.getArgs(task, "create")...)
	return syncCommand.CombinedOutput()
}

func (manager TasksSyncManager) syncUpdated(task Task) ([]byte, error) {
	log.Default().Println("Updating cloud scheduler task " + task.Name)
	syncCommand := exec.Command("gcloud", manager.getArgs(task, "update")...)
	return syncCommand.CombinedOutput()
}

func (manager QueuesSyncManager) getEntryName(queue Queue) string {
	return queue.Name
}

func (manager QueuesSyncManager) getXmlEntries() []Queue {
	return manager.Queues
}

func (manager QueuesSyncManager) getArgs(queue Queue, operationType string) []string {
	args := []string{
		"tasks", "queues", operationType, queue.Name, "--project", projectName,
	}
	if queue.MaxAttempts != "" {
		args = append(args, "--max-attempts", queue.MaxAttempts)
	}
	if queue.MaxBackoff != "" {
		args = append(args, "--max-backoff", queue.MaxBackoff)
	}
	if queue.MaxConcurrentDispatches != "" {
		args = append(args, "--max-concurrent-dispatches", queue.MaxConcurrentDispatches)
	}
	if queue.MaxDispatchesPerSecond != "" {
		args = append(args, "--max-dispatches-per-second", queue.MaxDispatchesPerSecond)
	}
	if queue.MaxDoublings != "" {
		args = append(args, "--max-doublings", queue.MaxDoublings)
	}
	if queue.MaxRetryDuration != "" {
		args = append(args, "--max-retry-duration", queue.MaxRetryDuration)
	}
	if queue.MinBackoff != "" {
		args = append(args, "--min-backoff", queue.MinBackoff)
	}
	if queue.RoutingOverride != "" {
		args = append(args, "--routing-override", queue.RoutingOverride)
	}
	if queue.MaxBurstSize != "" {
		args = append(args, "--max-burst-size", queue.MaxBurstSize)
	}
	args = append(args, "--format=json")
	return args
}

func (manager QueuesSyncManager) fetchExistingRecords() ExistingEntries {
	return getExistingEntries(exec.Command("gcloud", "tasks", "queues", "list", "--project="+projectName, "--location=us-central1", "--format=json"))
}

func (manager QueuesSyncManager) syncDeleted(taskName string) ([]byte, error) {
	// Default queue can't be deleted
	if taskName == "default" {
		return []byte{}, nil
	}
	log.Default().Println("Deleting cloud tasks queue " + taskName)
	cmd := exec.Command("gcloud", "tasks", "queues", "delete", taskName, "--project="+projectName, "--quiet")
	return cmd.CombinedOutput()
}

func (manager QueuesSyncManager) syncCreated(queue Queue) ([]byte, error) {
	log.Default().Println("Creating cloud tasks queue " + queue.Name)
	cmd := exec.Command("gcloud", manager.getArgs(queue, "create")...)
	return cmd.CombinedOutput()
}

func (manager QueuesSyncManager) syncUpdated(queue Queue) ([]byte, error) {
	log.Default().Println("Updating cloud tasks queue " + queue.Name)
	cmd := exec.Command("gcloud", manager.getArgs(queue, "update")...)
	return cmd.CombinedOutput()
}

func getExistingEntries(cmd *exec.Cmd) ExistingEntries {
	var allExistingEntries []ExistingEntry
	cmdGetExistingListOutput, cmdGetExistingListError := cmd.CombinedOutput()
	if cmdGetExistingListError != nil {
		panic("Failed to load existing entries: " + cmdGetExistingListError.Error())
	}
	err := json.Unmarshal(cmdGetExistingListOutput, &allExistingEntries)
	if err != nil {
		panic("Failed to parse existing entries: " + err.Error())
	}
	e := ExistingEntries{
		asAMap: map[string]bool{},
	}
	for i := 0; i < len(allExistingEntries); i++ {
		e.asAMap[allExistingEntries[i].Name[strings.LastIndex(allExistingEntries[i].Name, "/")+1:]] = true
	}
	return e
}

func main() {
	if len(os.Args) < 3 || os.Args[1] == "" || os.Args[2] == "" {
		panic("Error - Invalid Parameters.\nRequired params: 1 - config file path; 2 - project name;")
	}
	// Config file path
	configFileLocation := os.Args[1]
	// Project name where to submit the tasks
	projectName = os.Args[2]

	log.Default().Println("Filepath " + configFileLocation)
	xmlFile, err := os.Open(configFileLocation)
	if err != nil {
		panic(err)
	}
	defer xmlFile.Close()
	byteValue, _ := io.ReadAll(xmlFile)
	var xmlEntries XmlEntries
	if err := xml.Unmarshal(byteValue, &xmlEntries); err != nil {
		panic("Failed to parse xml file entries: " + err.Error())
	}

	if len(xmlEntries.Tasks) > 0 {
		log.Default().Println("Syncing cloud scheduler tasks from xml...")
		taskSyncManager := TasksSyncManager{
			Tasks: xmlEntries.Tasks,
		}
		sync[Task](taskSyncManager)
	}

	if len(xmlEntries.Queues) > 0 {
		log.Default().Println("Syncing cloud task queues from xml...")
		queuesSyncManager := QueuesSyncManager{
			Queues: xmlEntries.Queues,
		}
		sync[Queue](queuesSyncManager)
	}
}
