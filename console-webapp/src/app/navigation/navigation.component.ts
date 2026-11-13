// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

import { NestedTreeControl } from '@angular/cdk/tree';
import {
  Component,
  OnDestroy,
  OnInit,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { MatTreeNestedDataSource } from '@angular/material/tree';
import { NavigationEnd, Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { RouteWithIcon, routes, PATHS } from '../app.routes';
import { MaterialModule } from '../material.module';
import {
  RESTRICTED_ELEMENTS,
  UserLevelVisibility,
} from '../shared/directives/userLevelVisiblity.directive';

interface NavMenuNode extends RouteWithIcon {
  parentRoute?: RouteWithIcon;
}

/**
 * This component is responsible for rendering navigation menu based on the allowed routes
 * and keeping UI in sync when route changes (eg highlights selected route in the menu).
 */
@Component({
  selector: 'app-navigation',
  imports: [MaterialModule, UserLevelVisibility],
  templateUrl: './navigation.component.html',
  styleUrls: ['./navigation.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NavigationComponent implements OnInit, OnDestroy {
  renderRouter: boolean = true;
  currentUrl = signal('');
  treeControl = new NestedTreeControl<RouteWithIcon>((node) => node.children);
  dataSource = new MatTreeNestedDataSource<RouteWithIcon>();
  private subscription!: Subscription;

  hasChild = (_: number, node: RouteWithIcon) =>
    !!node.children && node.children.length > 0;

  constructor(protected router: Router) {
    this.dataSource.data = this.ngRoutesToNavMenuNodes(routes);
  }

  ngOnInit() {
    this.currentUrl.set(this.router.url);
    this.subscription = this.router.events.subscribe((navigationParams) => {
      if (navigationParams instanceof NavigationEnd) {
        this.currentUrl.set(navigationParams.urlAfterRedirects);
        this.syncExpandedNavigationWithRoute();
      }
    });
  }

  ngOnDestroy() {
    this.subscription && this.subscription.unsubscribe();
  }

  getElementId(node: RouteWithIcon) {
    if (node.path === PATHS.RegistrarComponent) {
      return RESTRICTED_ELEMENTS.REGISTRAR_ELEMENT;
    } else if (node.path === PATHS.UsersComponent) {
      return RESTRICTED_ELEMENTS.USERS;
    }
    return null;
  }

  syncExpandedNavigationWithRoute() {
    const activeParentNode = this.dataSource.data.find(
      (menuNode) =>
        this.hasChild(0, menuNode) &&
        menuNode.path &&
        this.router.isActive(menuNode.path, {
          paths: 'subset',
          queryParams: 'ignored',
          fragment: 'ignored',
          matrixParams: 'ignored',
        })
    );
    if (activeParentNode) {
      this.treeControl.expand(activeParentNode);
    }
  }

  onClick(node: NavMenuNode) {
    if (node.parentRoute) {
      this.router.navigate([node.parentRoute.path + '/' + node.path], {
        queryParamsHandling: 'merge',
      });
    } else {
      this.router.navigate([node.path], { queryParamsHandling: 'merge' });
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
