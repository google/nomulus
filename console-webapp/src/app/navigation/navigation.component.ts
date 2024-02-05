// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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

import { Component, effect } from '@angular/core';
import { Router } from '@angular/router';
import { RouteWithIcon, routes } from '../app-routing.module';
import { NestedTreeControl } from '@angular/cdk/tree';
import { MatTreeNestedDataSource } from '@angular/material/tree';

interface NavMenuNode extends RouteWithIcon {
  parentRoute?: RouteWithIcon;
}

@Component({
  selector: 'app-navigation',
  templateUrl: './navigation.component.html',
  styleUrls: ['./navigation.component.scss'],
})
export class NavigationComponent {
  renderRouter: boolean = true;
  treeControl = new NestedTreeControl<RouteWithIcon>((node) => node.children);
  dataSource = new MatTreeNestedDataSource<RouteWithIcon>();
  hasChild = (_: number, node: RouteWithIcon) =>
    !!node.children && node.children.length > 0;

  constructor(protected router: Router) {
    this.dataSource.data = this.ngRoutesToNavMenuNodes(routes);
  }

  onClick(node: NavMenuNode) {
    if (node.parentRoute) {
      this.router.navigate([node.parentRoute.path + '/' + node.path]);
    } else {
      this.router.navigate([node.path]);
    }
  }

  /**
   * We only want to use routes with titles and we want to provide easy reference to parent node
   */
  ngRoutesToNavMenuNodes(routes: RouteWithIcon[]): NavMenuNode[] {
    return routes
      .filter((r) => r.title)
      .map((r) => {
        if (r.children) {
          return {
            ...r,
            children: r.children
              .filter((r) => r.title)
              .map((childRoute) => {
                return {
                  ...childRoute,
                  parentRoute: r,
                };
              }),
          };
        }
        return r;
      });
  }
}
